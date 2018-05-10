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

import java.util.function.Function;
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
class ScopeCalculator {
  private static final String TAG = "ScopeCalculator";
  private final Utils utils;

  private static class ScopeCalculatingInfo {
    final TypeElement scope;
    final int size;
    // The dependency link that decides the scope.
    final ImmutableList<BindingKey> trail;

    ScopeCalculatingInfo(TypeElement scope, int size,
        List<BindingKey> trail) {
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

  class GetScopeFunction implements Function<DependencyInfo, DeclaredType> {
    private final DependencySourceType dependencySourceType;

    GetScopeFunction(DependencySourceType dependencySourceType) {
      this.dependencySourceType = dependencySourceType;
    }

    @Override
    @Nullable
    public DeclaredType apply(DependencyInfo dependencyInfo) {
      if (!dependencySourceType.equals(dependencyInfo.getDependencySourceType())) {
        return null;
      }
      messager.printMessage(Kind.NOTE, TAG + ".apply dI " + dependencyInfo);
      Element element;
      DeclaredType scopeType = null;
      switch (dependencySourceType) {
        case CTOR_INJECTED_CLASS:
          element = dependencyInfo.getSourceClassElement();
          scopeType = utils.getScopeType(element, scopeAliasCondenser);
          break;
        case MODULE:
          element = dependencyInfo.getProvisionMethodElement();
          scopeType = utils.getScopeType(element, scopeAliasCondenser);
          break;
        case EITHER_COMPONENT:
          // (Sub)component builder's scope is not specified but must be its component's parent
          // component scope.
          scopeType = getScopeForEitherComponent(dependencyInfo.getDependant());
          break;
        case EITHER_COMPONENT_BUILDER:
          // (Sub)component builder's scope is not specified but must be its component's parent
          // component scope.
          scopeType = getScopeForEitherComponentBuilder(dependencyInfo.getDependant());
          break;
        case COMPONENT_DEPENDENCIES_METHOD:
          // fall through
        case BINDS_INTANCE:
          scopeType = (DeclaredType) dependencyInfo.getCoreInjectorInfo().getScope().asType();
          break;
        default:
      }
      return scopeType;
    }
  }

  private final Collection<DependencyInfo> dependencyInfos;
  private final SetMultimap<BindingKey, DependencyInfo> dependencyMap;
  private final ScopeAliasCondenser scopeAliasCondenser;

  private final ProcessingEnvironment env;
  private final Messager messager;

  private final Set<BindingKey> bindingsRequired;
  private final Map<TypeElement, CoreInjectorInfo> componentToCoreInjectorMap;
  private final Map<TypeElement, CoreInjectorInfo> bothComponentBuilderToCoreInjectorMap;

  private Map<BindingKey, ? extends ScopeCalculatingInfo> explicitScopes;

  // Specified and calculated.
  private final Map<BindingKey, ScopeCalculatingInfo> allScopes = Maps.newHashMap();
  private final ScopeSizer scopeSizer;

  private boolean trailPrinted;
  
  private boolean initialized;

  private List<String> errors = new ArrayList<>();

  public ScopeCalculator(
      ScopeSizer scopeSizer,
      Collection<DependencyInfo> dependencyInfos,
      Set<BindingKey> keysRequired,
      Map<TypeElement, CoreInjectorInfo> componentToCoreInjectorMap,
      Map<TypeElement, CoreInjectorInfo> bothComponentBuilderToCoreInjectorMap,
      ScopeAliasCondenser scopeAliasCondenser,
      ProcessingEnvironment env, Utils utils) {
    this.scopeSizer = scopeSizer;
    this.env = env;
    this.messager = env.getMessager();
    this.dependencyInfos = dependencyInfos;
    this.bindingsRequired = keysRequired;
    this.componentToCoreInjectorMap = componentToCoreInjectorMap;
    this.bothComponentBuilderToCoreInjectorMap = bothComponentBuilderToCoreInjectorMap;
    this.scopeAliasCondenser = scopeAliasCondenser;
    dependencyMap = DependencyCollector.collectionToMultimap(dependencyInfos);
    this.utils = utils;
  }

  /**
   * Returns the biggest scope that can be applied to the given
   * {@link BindingKey}. Crash if no scope is applicable, which means a bug in
   * code being processed.
   */
  public TypeElement calculate(BindingKey key) {
    Preconditions.checkState(initialized, "ScopeCalculator is not initialized yet.");
    ScopeCalculatingInfo info = allScopes.get(key);
    Preconditions.checkNotNull(info, "Did not find scope info for %s", key);
    return info.scope;
  }

  public List<String> initialize() {
    explicitScopes = getExplicitScopes();
    allScopes.putAll(explicitScopes);
    allScopes.putAll(getEitherComponentScopes());
    allScopes.putAll(getEitherComponentBuilderScopes());
    allScopes.putAll(getComponentDependenciesScopes());
    allScopes.putAll(getBindsInstanceScopes());

    dumpAllScopes(TAG + " all scopes before calc: ");

    for (BindingKey key : bindingsRequired) {
      if (!allScopes.containsKey(key)) {
        calculateInternal(key, Lists.<BindingKey>newArrayList());
      }
    }

    dumpAllScopes(TAG + " all scopes after calc: ");

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

    return errors;
  }

  private void dumpAllScopes(String msg) {
    messager.printMessage(Kind.NOTE, msg);
    for (Map.Entry<BindingKey, ScopeCalculatingInfo> entry : allScopes.entrySet()) {
      messager.printMessage(Kind.NOTE,
          String.format("%s: %s -> %s", TAG, entry.getKey(), entry.getValue()));
    }
    messager.printMessage(Kind.NOTE, msg + " done.");
  }

  public Set<BindingKey> getExplicitScopedKeys() {
    Preconditions.checkState(initialized);
    return explicitScopes.keySet();
  }

  private Map<BindingKey, ScopeCalculatingInfo> getExplicitScopes() {
    Map<BindingKey, ScopeCalculatingInfo> result =
        collectDirectScopes(new GetScopeFunction(DependencySourceType.CTOR_INJECTED_CLASS));
    result.putAll(collectDirectScopes(new GetScopeFunction(DependencySourceType.MODULE)));
    return result;
  }

  private Map<BindingKey, ScopeCalculatingInfo> getEitherComponentBuilderScopes() {
    return  collectDirectScopes(
        new GetScopeFunction(DependencySourceType.EITHER_COMPONENT));
  }

  private Map<BindingKey, ScopeCalculatingInfo> getEitherComponentScopes() {
    return  collectDirectScopes(
        new GetScopeFunction(DependencySourceType.EITHER_COMPONENT_BUILDER));
  }

  private Map<BindingKey, ScopeCalculatingInfo> getComponentDependenciesScopes() {
    return  collectDirectScopes(
        new GetScopeFunction(DependencySourceType.COMPONENT_DEPENDENCIES_METHOD));
  }

  private Map<BindingKey, ScopeCalculatingInfo> getBindsInstanceScopes() {
    return  collectDirectScopes(
        new GetScopeFunction(DependencySourceType.BINDS_INTANCE));
  }

  /**
   * Returns scopes found for the give toScope. This is for scope that does not need calculation
   * with dependencies, e.g., explicity, (sub)component builder and those from component
   * dependencies. If toScope return null, the entry will be ignored.
   */
  private Map<BindingKey, ScopeCalculatingInfo> collectDirectScopes(
      Function<DependencyInfo, DeclaredType> toScope) {
    Map<BindingKey, ScopeCalculatingInfo> result = new HashMap<>();
    for (DependencyInfo info : dependencyInfos) {
      DeclaredType scopeType = toScope.apply(info);
      if (scopeType != null) {
        if (!info.isUnique()) {
          // TODO, handle scoped multibinding.
          messager.printMessage(Kind.WARNING,
              String.format("multibinding with scope %s, info: %s", scopeType, info));
          // messager.printMessage(Kind.WARNING,
          //     String.format("multibinding's scope is ignored: %s", info));
          //continue;
        }
        TypeElement scope = (TypeElement) scopeType.asElement();
        int scopeSize = scopeSizer.getScopeSize(scope);
        if (scopeSize != -1) {
          result.put(
              info.getDependant(),
              new ScopeCalculatingInfo(scope, scopeSize, new ArrayList<BindingKey>()));
        }
      }
    }
    return result;
  }

  private DeclaredType getScopeForEitherComponent(BindingKey key) {
    messager.printMessage(Kind.NOTE, TAG + ".getScopeForEitherComponent. Key: " + key);
    TypeElement component = utils.getTypeElement(key);
    DeclaredType scopeType =
        (DeclaredType) componentToCoreInjectorMap.get(component).getScope().asType();
    scopeType =
        (DeclaredType)
            scopeAliasCondenser.getCoreScopeForAlias((TypeElement) scopeType.asElement()).asType();
    return scopeType;
  }

  private DeclaredType getScopeForEitherComponentBuilder(BindingKey key) {
    // messager.printMessage(Kind.NOTE, TAG + ".getScopeForEitherComponentBuilder. Key: " + key);
    DeclaredType scopeType =
        (DeclaredType)
            bothComponentBuilderToCoreInjectorMap
                .get(utils.getTypeElement(key))
                .getScope()
                .asType();
    scopeType =
        (DeclaredType)
            scopeAliasCondenser
                .getCoreScopeForAlias((TypeElement) scopeType.asElement())
                .asType();
    return scopeType;
  }

  /*
   * Verifies there is no conflict, i.e., large scoped Class depends on smaller
   * scoped Class.
   */
  private void verifyScopes() {
    for (BindingKey key : bindingsRequired) {
//      messager.printMessage(Kind.NOTE, String.format("VerifyScope for key: %s", key));
      ScopeCalculatingInfo scopeCalculatingInfo = allScopes.get(key);
      if (scopeCalculatingInfo == null) {
        errors.add(String.format("Scope for %s cannot be determined.", key));
        continue;
      }
      TypeElement scope = scopeCalculatingInfo.scope;
      for (DependencyInfo dependencyInfo : dependencyMap.get(key)) {
        for (BindingKey k : dependencyInfo.getDependencies()) {
          ScopeCalculatingInfo sci = getValueHandlingDagger(allScopes, k);
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
   * Returns provided {@link BindingKey} for the give one. If the given one provided explicitly,
   * it is returned. Otherwise, if it is a Dagger built-in supported generic type, its actual type
   * parameter is returned. If still cannot find, return null.
   */
  @Nullable
  private <T> T getValueHandlingDagger(Map<BindingKey, T> map, BindingKey key) {
    T result = map.get(key);
    if (result != null) {
      return result;
    }
    key = utils.getElementKeyForParameterizedBinding(key);
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
  private ScopeCalculatingInfo calculateInternal(BindingKey key, List<BindingKey> trail) {
    if (trail.contains(key)) {
      messager.printMessage(Kind.WARNING, String.format("Circle! At %s, trail: %s", key, trail));
      return null;
    }
    ScopeCalculatingInfo result;
    trail.add(key);

    // Sign of infinite call.
    if (!trailPrinted && trail.size() > 100) {
      trailPrinted = true;
      messager.printMessage(
          Kind.ERROR, "Large dependency chain found. Check for circular dependencies: " + trail);
    }

    ScopeCalculatingInfo scopeCalculatingInfo = allScopes.get(key);
    if (scopeCalculatingInfo != null) {
      result =
          new ScopeCalculatingInfo(
              scopeCalculatingInfo.scope, scopeCalculatingInfo.size, Lists.newArrayList(trail));
    } else {
      // TODO: fix this, should looking for Lazy<Foo> before Foo.
      if (utils.isProviderOrLazy(key)){
        result = calculateInternal(utils.getElementKeyForParameterizedBinding(key), trail);
      } else {
        Set<DependencyInfo> dependencies = utils.getDependencyInfo(dependencyMap, key);
        if (utils.isBindsOptionalOf(dependencies)
            && utils.isBindsOptionalOfPresent(dependencyMap, dependencies)) {
          result = calculateInternal(utils.getElementKeyForParameterizedBinding(key), trail);
        } else {
          result =
              new ScopeCalculatingInfo(
                  scopeSizer.getLargestScope().getScope(),
                  scopeSizer.getLargestScopeSize(),
                  trail);

          if (dependencies == null) {
            errors.add(TAG + ": Did not find key: " + key);
          } else {

            for (DependencyInfo dependencyInfo : dependencies) {
              for (BindingKey k : dependencyInfo.getDependencies()) {
                ScopeCalculatingInfo sci = calculateInternal(k, trail);

                // Handling circle. See the method comment.
                if (sci == null) {
                  continue;
                }

                if (sci.size < result.size) {
                  TypeElement commonChild =
                      scopeSizer.getLargestDependantScope(sci.scope, result.scope);
                  int size = scopeSizer.getScopeSize(commonChild);
                  Preconditions.checkState(size != -1);
                  messager.printMessage(
                      Kind.NOTE,
                      String.format(
                          "calculateInternal narrowed by key %s to %d old result: %s, sci: %s",
                          k, size, result, sci));

                  result = new ScopeCalculatingInfo(commonChild, size, sci.trail);
                }
              }
            }
          }
        }
      }
      allScopes.put(key, result);
    }

    trail.remove(trail.size() - 1);
    result = new ScopeCalculatingInfo(result.scope, result.size, trail);
    messager.printMessage(Kind.NOTE, String.format("calculateInternal key %s. result %s", key, result));
    return result;
  }
}
