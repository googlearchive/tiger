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

import java.util.Objects;

import javax.annotation.Nullable;
import javax.lang.model.element.TypeElement;

/**
 * Core injectors info. Each scope and its potential synonyms share one {@link CoreInjectorInfo}.
 */
public class CoreInjectorInfo {
  private final TypeElement scope;
  private final String name;

  public CoreInjectorInfo(TypeElement scope) {
    this(scope, null);
  }

  public CoreInjectorInfo(TypeElement scope, @Nullable String name) {
    this.scope = Preconditions.checkNotNull(scope, "null scope?");
    this.name = name != null ? name : scope.getQualifiedName().toString().replace(".", "_");
  }

  public TypeElement getScope() {
    return scope;
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof CoreInjectorInfo)) {
      return false;
    }
    CoreInjectorInfo that = (CoreInjectorInfo) obj;

    return Utils.isTypeElementEqual(this.scope, that.scope) && Objects.equals(this.name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scope, name);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("scope", scope).add("name", name).toString();
  }
}
