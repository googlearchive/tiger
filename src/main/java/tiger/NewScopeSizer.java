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

import javax.lang.model.element.TypeElement;

/**
 * Provides scope size data. Sizes only have relative meaning. Parent scope is bigger than child
 * scope.
 */
public interface NewScopeSizer {

  /**
   * Larger values mean longer life cycles. The values are only meaningful for
   * relational operations, other uses are meaningless.
   */
  int getScopeSize(ComponentInfo scope);

  int getScopeSize(TypeElement scope);

  ComponentInfo getLargestScope();

  int getLargestScopeSize();

  boolean isSmallestScope(ComponentInfo scope);

  /**
   * Return the largest scope that can depends on both given scopes.
   */
  ComponentInfo getLargestDependantScope(ComponentInfo scope1,
      ComponentInfo scope2);
  
  TypeElement getLargestDependantScope(TypeElement scope1,
      TypeElement scope2);

  /**
   * Return if dependent depends on dependency.
   */
  boolean canDependOn(ComponentInfo dependent, ComponentInfo dependency);
  boolean canDependOn(TypeElement dependent, TypeElement dependency);
}
