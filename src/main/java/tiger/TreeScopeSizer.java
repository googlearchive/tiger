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
import javax.lang.model.element.TypeElement;

/**
 * {@link NewScopeSizer} based on the given scope tree. Size is 0 based.
 */
public class TreeScopeSizer implements NewScopeSizer {
  
  // Map from child to parent.
  private final Map<ComponentInfo, ComponentInfo> scopeTree;
  private final Map<ComponentInfo, Integer> scopeToDepthMap = new HashMap<>();
  private final int largestSize;
  private final int smallestSize = 0;
  private final ComponentInfo largestScope;
  
  public TreeScopeSizer(Map<ComponentInfo, ComponentInfo> scopeTree,
      @Nullable ComponentInfo rootComponentInfo) {
    this.scopeTree = scopeTree;
    if (!scopeTree.isEmpty()) {
      rootComponentInfo = Iterables
          .getOnlyElement(Sets.difference(Sets.newHashSet(scopeTree.values()), scopeTree.keySet()));
    } else {
      Preconditions.checkNotNull(rootComponentInfo);
    }
    scopeToDepthMap.put(rootComponentInfo, 0);
    largestScope = rootComponentInfo;
    for (ComponentInfo componentInfo : scopeTree.keySet()) {
      int depth = findTreeNodeDepth(scopeTree, componentInfo);
      scopeToDepthMap.put(componentInfo, depth);
    }

    int depth = 0;
    for (Map.Entry<ComponentInfo, Integer> entry : scopeToDepthMap.entrySet()) {
      int value = entry.getValue();
      if (value > depth) {
        depth = value;
      }
    }
    largestSize = depth;
    System.out.println(String.format("scopeTree: %s", scopeTree));
    System.out.println(String.format("scopeToDepthMap: %s", scopeToDepthMap));
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

  @Override
  public int getScopeSize(ComponentInfo scope) {
    return largestSize - scopeToDepthMap.get(scope);
  }

  @Override
  public int getScopeSize(TypeElement scope) {
    for (ComponentInfo componentInfo : scopeToDepthMap.keySet()) {
      if (componentInfo.getScope().getQualifiedName().contentEquals(scope.getQualifiedName())) {
        return getScopeSize(componentInfo);
      }
    }
    throw new RuntimeException(
        String.format("Did not find component for scope: %s, tree: %s, map: %s", scope, scopeTree,
            scopeToDepthMap));
  }

  @Override
  public ComponentInfo getLargestScope() {
    return largestScope;
  }

  @Override
  public int getLargestScopeSize() {
    return largestSize;
  }

  @Override
  public boolean isSmallestScope(ComponentInfo scope) {
    return getScopeSize(scope) == smallestSize;
  }

  @Override
  public ComponentInfo getLargestDependantScope(ComponentInfo scope1, ComponentInfo scope2) {
    return getScopeSize(scope1) > getScopeSize(scope2) ? scope2 : scope1;
  }

  @Override
  public TypeElement getLargestDependantScope(TypeElement scope1, TypeElement scope2) {
    return getScopeSize(scope1) > getScopeSize(scope2) ? scope2 : scope1;
  }

  @Override
  public boolean canDependOn(ComponentInfo dependent, ComponentInfo dependency) {
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
    return canDependOn(findComponentInfoForScopeOrThrow(dependent),
        findComponentInfoForScopeOrThrow(dependency));
  }
  
  private ComponentInfo findComponentInfoForScopeOrThrow(TypeElement scopeType) {
    for (ComponentInfo ci: scopeToDepthMap.keySet()) {
      if (Utils.isTypeElementEqual(ci.getScope(), scopeType)) {
        return ci;
      }
    }
    throw new RuntimeException(String.format("No ComponentInfo for scope %s found. Size map: %s",
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
