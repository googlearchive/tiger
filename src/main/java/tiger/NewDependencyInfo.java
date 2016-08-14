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
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import dagger.Provides.Type;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Stores dependency information, which is collected from either modules or ctor injected classes.
 */
public class NewDependencyInfo {
  private final NewBindingKey dependant;
  /**
   * A dependency is a {@link NewBindingKey} which includes the type and the
   * qualifier annotation.
   */
  private final Set<NewBindingKey> dependencies;
  /** The class that provides this dependency, either a module or a ctor injected class. */
  private final TypeElement sourceClassElement;
  @Nullable private final ExecutableElement provisionMethodElement;
  /** Dagger biding type. */
  private final Type type;

  /**
   * Constructs a {@link NewDependencyInfo} from the given parameters.
   */
  public NewDependencyInfo(
      NewBindingKey dependant,
      Set<NewBindingKey> dependencies,
      TypeElement sourceClassElement,
      Type type) {
    this(dependant, dependencies, sourceClassElement, null, type);
  }

  /**
   * Constructs a {@link NewDependencyInfo} from the given parameters.
   */
  public NewDependencyInfo(
      NewBindingKey dependant,
      Set<NewBindingKey> dependencies,
      TypeElement sourceClassElement,
      @Nullable ExecutableElement provisionMethodElement,
      Type type) {
    this.dependant = dependant;
    this.dependencies = new HashSet<>(dependencies);
    this.sourceClassElement = Preconditions.checkNotNull(sourceClassElement);
    this.provisionMethodElement = provisionMethodElement;
    this.type = type;
  }

  public NewBindingKey getDependant() {
    return dependant;
  }

  public Set<NewBindingKey> getDependencies() {
    return dependencies;
  }

  public TypeElement getSourceClassElement() {
    return sourceClassElement;
  }

  @Nullable
  public ExecutableElement getProvisionMethodElement() {
    return provisionMethodElement;
  }

  public Type getType() {
    return type;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("dependant", dependant)
        .add("dependencies", dependencies)
        .add("sourceClassElement", sourceClassElement)
        .add("provisionMethodElement", provisionMethodElement)
        .add("type", type)
        .toString();
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(dependencies, sourceClassElement, provisionMethodElement, type);
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof NewDependencyInfo)) {
      return false;
    }
    NewDependencyInfo that = (NewDependencyInfo) object;
    return getDependant().equals(that.getDependant())
        && Objects.equal(getDependencies(), that.getDependencies())
        && Objects.equal(this.getSourceClassElement(), that.getSourceClassElement())
        && Objects.equal(this.getProvisionMethodElement(), that.getProvisionMethodElement())
        && this.getType().equals(that.getType());
  }

  public boolean isUnique() {
    return type.equals(Type.UNIQUE);
  }

  public boolean isSet() {
    return type.equals(Type.SET) || type.equals(Type.SET_VALUES);
  }
  
  private boolean isMap() {
    return type.equals(Type.MAP);
  }

  public boolean isMultiBinding() {
    return isSet() || isMap();
  }
}
