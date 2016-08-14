// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package tiger;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic.Kind;

/**
 * Calculates the scope of a key. For a key that already has scope, the scope it
 * returned. For the unscoped key, the scope is calculated with the following 2
 * rules:
 * <ol>
 * <li>1. if key A depends on key B which is of scope X, then A's scope must be
 * equal or smaller than X
 * <li>2. if key A is depended by key B which is of scope Y, then A's scope must
 * be equal or bigger than Y.
 * </ol>
 * The scope of longer life cycle is bigger. If there are more than 1 scopes
 * allowed, we choose the biggest one to minimize the re-instantiation. If there
 * are no scopes allowed, it is an error that will cause failure.
 */
class NewScopeCalculator {
  private static final String TAG = "NewScopeCalculator";
  private static class NewScopeCalculatingInfo {
    final TypeElement scope;
    final int size;
    // The dependency link that decides the scope.
    final ImmutableList<NewBindingKey> trail;

    NewScopeCalculatingInfo(TypeElement scope, int size,
        List<NewBindingKey> trail) {
      this.scope = scope;
      this.size = size;
      this.trail = ImmutableList.copyOf(trail);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("scope", scope)
          .add("size", size)
          .add("trail", trail)
          .toString();
    }
  }

  private final Collection<NewDependencyInfo> dependencyInfos;
  private final SetMultimap<NewBindingKey, NewDependencyInfo> dependencyMap;

  private final Messager messager;

  private final Set<NewBindingKey> bindingsRequired;

  // Specified and calculated.
  private final Map<NewBindingKey, NewScopeCalculatingInfo> allScopes = Maps.newHashMap();
  private final NewScopeSizer scopeSizer;

  private boolean trailPrinted;
  
  private boolean initialized;

  private List<String> errors = new ArrayList<>();

  public NewScopeCalculator(
      NewScopeSizer scopeSizer,
      Collection<NewDependencyInfo> dependencyInfos,
      Set<NewBindingKey> keysRequired,
      ProcessingEnvironment env) {
    this.scopeSizer = scopeSizer;
    this.messager = env.getMessager();
    this.dependencyInfos = dependencyInfos;
    this.bindingsRequired = keysRequired;
    dependencyMap = NewDependencyCollector.collectionToMultimap(dependencyInfos);
  }

  /**
   * Returns the biggest scope that can be applied to the given
   * {@link NewBindingKey}. Crash if no scope is applicable, which means a bug in
   * code being processed.
   */
  public TypeElement calculate(NewBindingKey key) {
    Preconditions.checkState(initialized, "ScopeCalculator is not initialized yet.");
    NewScopeCalculatingInfo info = allScopes.get(key);
    Preconditions.checkNotNull(info, "Did not find scope info for %s", key);
    return info.scope;
  }

  public List<String> initialize() {
    allScopes.putAll(getExplicitScopes());

    for (NewBindingKey key : bindingsRequired) {
      if (!allScopes.containsKey(key)) {
        calculateInternal(key, Lists.<NewBindingKey>newArrayList());
      }
    }

    if (!allScopes.keySet().containsAll(bindingsRequired)) {
      errors.add(
          String.format(
              "Scope of required keys not calculated.\nDiff: %s\nRequired: %s\nCalculated: %s",
              Sets.difference(bindingsRequired, allScopes.keySet()),
              bindingsRequired,
              allScopes));
    }

    verifyScopes();

    if (errors.isEmpty()) {
      initialized = true;
    }
    
//    messager.printMessage(Kind.NOTE, String.format("%s all scopes:", TAG));
//    for (Map.Entry<NewBindingKey, NewScopeCalculatingInfo> entry : allScopes.entrySet()) {
//      messager.printMessage(Kind.NOTE,
//          String.format("%s: %s -> %s", TAG, entry.getKey(), entry.getValue()));
//    }

    return errors;
  }

  public Set<NewBindingKey> getExplicitScopedKeys() {
    Preconditions.checkState(initialized);
    return getExplicitScopes().keySet();
  }

  private Map<NewBindingKey, ? extends NewScopeCalculatingInfo> getExplicitScopes() {
    Map<NewBindingKey, NewScopeCalculatingInfo> result = new HashMap<>();
    for (NewDependencyInfo info : dependencyInfos) {
      NewBindingKey key = info.getDependant();
      Element ele =
          info.getProvisionMethodElement() != null
              ? info.getProvisionMethodElement()
              : info.getSourceClassElement();
      DeclaredType scopeType = Utils.getScopeType(ele);
      if (scopeType != null) {
        if (!info.isUnique()) {
          messager.printMessage(Kind.ERROR,
              String.format("Non-unique binding must be unscoped: %s", info));
        }
        TypeElement scope = (TypeElement) scopeType.asElement();
        int scopeSize = scopeSizer.getScopeSize(scope);
        result.put(key,
            new NewScopeCalculatingInfo(scope, scopeSize, new ArrayList<NewBindingKey>()));
      }
    }
    return result;
  }

  /*
   * Verifies there is no conflict, i.e., large scoped Class depends on smaller
   * scoped Class.
   */
  private void verifyScopes() {
    for (NewBindingKey key : bindingsRequired) {
//      messager.printMessage(Kind.NOTE, String.format("VerifyScope for key: %s", key));
      NewScopeCalculatingInfo scopeCalculatingInfo = allScopes.get(key);
      if (scopeCalculatingInfo == null) {
        errors.add(String.format("Scope for %s cannot be determined.", key));
        continue;
      }
      TypeElement scope = scopeCalculatingInfo.scope;
      for (NewDependencyInfo dependencyInfo : dependencyMap.get(key)) {
        for (NewBindingKey k : dependencyInfo.getDependencies()) {
          NewScopeCalculatingInfo sci = getValueHandlingDagger(allScopes, k);
          if (sci == null) {
            errors.add(
                String.format("Scope of %s unavailable, which is required by %s of scope %s.",
                    k, key, scope));
            continue;
          }

          TypeElement s = sci.scope;
          if (!scopeSizer.canDependOn(scope, s)) {
            errors.add(
                String.format(
                    "Wrong scope. Dependent: %s scope: %s dependency: %s scope: %s.",
                    key,
                    scope,
                    k,
                    s));
          }
        }
      }
    }
  }

  /**
   * Returns provided {@link NewBindingKey} for the give one. If the given one provided explicitly,
   * it is returned. Otherwise, if it is a Dagger built-in supported generic type, its actual type
   * parameter is returned. If still cannot find, return null.
   */
  @Nullable
  private <T> T getValueHandlingDagger(Map<NewBindingKey, T> map, NewBindingKey key) {
    T result = map.get(key);
    if (result != null) {
      return result;
    }
    key = Utils.getElementKeyForBuiltinBinding(key);
    if (key != null) {
      result = map.get(key);
      if (result != null) {
        return result;
      }
    }

    return null;
  }

  /**
   * Calculates scope for the key and all the keys without a scope that are depended
   * (directly or indirectly) BY it. The scope is the smallest descendant of all the dependencies.
   * NOTE: return null if the key exists in trail, which is a circle. Just ignore it, with warning.
   */
  @Nullable
  private NewScopeCalculatingInfo calculateInternal(NewBindingKey key, List<NewBindingKey> trail) {
//    messager.printMessage(Kind.NOTE, String.format("calculateInternal %s. trail %s", key, trail));
    if (trail.contains(key)) {
      messager.printMessage(Kind.WARNING, String.format("Circle! At %s, trail: %s", key, trail));
      return null;
    }
    NewScopeCalculatingInfo result;
    trail.add(key);

    // Sign of infinite call.
    if (!trailPrinted && trail.size() > 100) {
      trailPrinted = true;
      messager.printMessage(
          Kind.ERROR, "Large dependency chain found. Check for circular dependencies: " + trail);
    }

    NewScopeCalculatingInfo scopeCalculatingInfo = allScopes.get(key);
    if (scopeCalculatingInfo != null) {
      result =
          new NewScopeCalculatingInfo(
              scopeCalculatingInfo.scope, scopeCalculatingInfo.size, Lists.newArrayList(trail));
    } else {
      if (Utils.hasBuiltinBinding(key)){
        result = calculateInternal(Utils.getElementKeyForBuiltinBinding(key), trail);
      } else {
        result = new NewScopeCalculatingInfo(scopeSizer.getLargestScope().getScope(),
            scopeSizer.getLargestScopeSize(), trail);
        Set<NewDependencyInfo> dependencies = Utils.getDependencyInfo(dependencyMap, key);
        if (dependencies == null) {
          errors.add("Did not find key: " + key);
        } else {
          for (NewDependencyInfo dependencyInfo : dependencies) {
            for (NewBindingKey k : dependencyInfo.getDependencies()) {
              NewScopeCalculatingInfo sci = calculateInternal(k, trail);

              // Handling circle. See the method comment.
              if (sci == null) {
                continue;
              }

              if (sci.size <= result.size) {
                TypeElement commonChild =
                    scopeSizer.getLargestDependantScope(sci.scope, result.scope);
                int size = scopeSizer.getScopeSize(commonChild);
                result = new NewScopeCalculatingInfo(commonChild, size, sci.trail);
              }
            }
          }
        }

      }
      allScopes.put(key, result);
    }

    trail.remove(trail.size() - 1);
    return result;
  }
}
