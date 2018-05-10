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
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

/**
 * {@link ScopeSizer} based on the given scope tree. Size is 0 based.
 */
public class TreeScopeSizer implements ScopeSizer {
  
  // Map from child to parent.
  private final Map<CoreInjectorInfo, CoreInjectorInfo> scopeTree;
  private final Map<CoreInjectorInfo, Integer> scopeToDepthMap = new HashMap<>();
  private final int largestSize;
  private final int smallestSize = 0;
  private final CoreInjectorInfo largestScope;
  private final Messager messager;

  public TreeScopeSizer(Map<CoreInjectorInfo, CoreInjectorInfo> scopeTree,
      @Nullable CoreInjectorInfo rootCoreInjectorInfo,
      Messager messager) {
    this.scopeTree = scopeTree;
    this.messager = messager;
    if (!scopeTree.isEmpty()) {
      rootCoreInjectorInfo = Iterables
          .getOnlyElement(Sets.difference(Sets.newHashSet(scopeTree.values()), scopeTree.keySet()));
    } else {
      Preconditions.checkNotNull(rootCoreInjectorInfo);
    }
    scopeToDepthMap.put(rootCoreInjectorInfo, 0);
    largestScope = rootCoreInjectorInfo;
    for (CoreInjectorInfo coreInjectorInfo : scopeTree.keySet()) {
      int depth = findTreeNodeDepth(scopeTree, coreInjectorInfo);
      scopeToDepthMap.put(coreInjectorInfo, depth);
    }

    int depth = 0;
    for (Map.Entry<CoreInjectorInfo, Integer> entry : scopeToDepthMap.entrySet()) {
      int value = entry.getValue();
      if (value > depth) {
        depth = value;
      }
    }
    largestSize = depth;
    messager.printMessage(Kind.NOTE, "scopeTree: %s" + scopeTree);
    messager.printMessage(Kind.NOTE, "scopeToDepthMap: " + scopeToDepthMap);
  }

  /**
   * Returns the depth of node in tree. Root's depth is 0.
   */
  private <T> int findTreeNodeDepth(Map<T, T> tree, T node) {
    int result = 0;
    T parent = tree.get(node);
    while (parent != null) {
      result ++;
      node = parent;
      parent = tree.get(node);
    }
    return result;
  }

  /**
   * Return the size of the scope, -1 if it is not in the tree.
   */
  @Override
  public int getScopeSize(CoreInjectorInfo scope) {
    if (!scopeToDepthMap.containsKey(scope)) {
      return -1;
    }
    return largestSize - scopeToDepthMap.get(scope);
  }

  /**
   * Return the size of the scope, -1 if it is not in the tree.
   */
  @Override
  public int getScopeSize(TypeElement scope) {
    for (CoreInjectorInfo coreInjectorInfo : scopeToDepthMap.keySet()) {
      if (coreInjectorInfo.getScope().getQualifiedName().contentEquals(scope.getQualifiedName())) {
        int result = getScopeSize(coreInjectorInfo);
        Preconditions.checkState(result != -1);
        return result;
      }
    }
    messager.printMessage(Kind.NOTE,
        String.format("Did not find component for scope: %s, tree: %s, map: %s\n", scope, scopeTree,
            scopeToDepthMap));
    throw new RuntimeException();
    // return -1;
  }

  @Override
  public CoreInjectorInfo getLargestScope() {
    return largestScope;
  }

  @Override
  public int getLargestScopeSize() {
    return largestSize;
  }

  @Override
  public boolean isSmallestScope(CoreInjectorInfo scope) {
    return getScopeSize(scope) == smallestSize;
  }

  @Override
  public CoreInjectorInfo getLargestDependantScope(
      CoreInjectorInfo scope1, CoreInjectorInfo scope2) {
    Preconditions.checkArgument(getScopeSize(scope1) != -1 && getScopeSize(scope2) != -1);
    return getScopeSize(scope1) > getScopeSize(scope2) ? scope2 : scope1;
  }

  @Override
  public TypeElement getLargestDependantScope(TypeElement scope1, TypeElement scope2) {
    Preconditions.checkArgument(getScopeSize(scope1) != -1 && getScopeSize(scope2) != -1);
    return getScopeSize(scope1) > getScopeSize(scope2) ? scope2 : scope1;
  }

  @Override
  public boolean canDependOn(CoreInjectorInfo dependent, CoreInjectorInfo dependency) {
    while (dependent != null) {
      if (dependent.equals(dependency)) {
        return true;
      } else {
        dependent = scopeTree.get(dependent);
      }
    }
    return false;
  }
  
  @Override
  public boolean canDependOn(TypeElement dependent, TypeElement dependency) {
    return canDependOn(findCorInjectorForScopeOrThrow(dependent),
        findCorInjectorForScopeOrThrow(dependency));
  }
  
  private CoreInjectorInfo findCorInjectorForScopeOrThrow(TypeElement scopeType) {
    for (CoreInjectorInfo ci: scopeToDepthMap.keySet()) {
      if (Utils.isTypeElementEqual(ci.getScope(), scopeType)) {
        return ci;
      }
    }
    throw new RuntimeException(String.format("No CoreInjectorInfo for scope %s found. Size map: %s",
        scopeType, scopeToDepthMap));
  }
  
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("scopeTree", scopeTree)
      .add("scopeToDepthMap", scopeToDepthMap)
      .add("largestScope", largestScope)
      .add("largestSize", largestSize)
      .add("smallestScopeSize", smallestSize)
      .toString();
  }
  
}
