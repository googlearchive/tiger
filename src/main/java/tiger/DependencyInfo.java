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

import static tiger.ProvisionType.MAP;
import static tiger.ProvisionType.SET;
import static tiger.ProvisionType.SET_VALUES;
import static tiger.ProvisionType.UNIQUE;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Stores dependency information, which is collected from either modules or ctor injected classes.
 */
public class DependencyInfo {
  private final ProvisionType type;

  /** Where is the dependent from */
  private final DependencySourceType dependencySourceType;

  private final BindingKey dependant;
  /**
   * A dependency is a {@link BindingKey} which includes the type and the
   * qualifier annotation.
   */
  private final Set<BindingKey> dependencies;
  /**
   * The class that provides this dependency, either a module or a ctor injected class, the class
   * itself for (sub)components and their builders. TODO: set it to Builder instead of
   * (sub)component.Builder for {@link DependencySourceType#BINDS_INTANCE}.
   */
  private final TypeElement sourceClassElement;

  @Nullable private final ExecutableElement provisionMethodElement;
  private final CoreInjectorInfo coreInjectorInfo;

  /** Constructs a {@link DependencyInfo} from the given parameters. */
  public DependencyInfo(
      DependencySourceType dependencySourceType,
      BindingKey dependant,
      Set<BindingKey> dependencies,
      @Nullable TypeElement sourceClassElement,
      ProvisionType type) {
    this(dependencySourceType, dependant, dependencies, sourceClassElement, null, type, null);
  }

  /** Constructs a {@link DependencyInfo} from the given parameters. */
  public DependencyInfo(
      DependencySourceType dependencySourceType,
      BindingKey dependant,
      Set<BindingKey> dependencies,
      @Nullable TypeElement sourceClassElement,
      @Nullable ExecutableElement provisionMethodElement,
      ProvisionType type) {
    this(
        dependencySourceType,
        dependant,
        dependencies,
        sourceClassElement,
        provisionMethodElement,
        type,
        null);
  }

  public DependencyInfo(
      DependencySourceType dependencySourceType,
      BindingKey dependant,
      Set<BindingKey> dependencies,
      @Nullable TypeElement sourceClassElement,
      @Nullable ExecutableElement provisionMethodElement,
      ProvisionType type,
      @Nullable CoreInjectorInfo coreInjectorInfo) {
    // Preconditions.checkArgument(
    //     (coreInjectorInfo != null)
    //         == (Sets.newHashSet(
    //                 DependencySourceType.COMPONENT_DEPENDENCIES_METHOD,
    //                 DependencySourceType.BINDS_INTANCE,
    //                 DependencySourceType.COMPONENT_DEPENDENCIES_ITSELF)
    //             .contains(dependencySourceType)),
    //     " coreInjectorInfo: "
    //         + coreInjectorInfo
    //         + " dependencySourceType: "
    //         + dependencySourceType);
    this.dependencySourceType = dependencySourceType;
    this.dependant = dependant;
    this.dependencies = new HashSet<>(dependencies);
    this.sourceClassElement = sourceClassElement;
    this.provisionMethodElement = provisionMethodElement;
    this.type = type;
    this.coreInjectorInfo = coreInjectorInfo;
  }

  public DependencySourceType getDependencySourceType() {
    return dependencySourceType;
  }

  public BindingKey getDependant() {
    return dependant;
  }

  public Set<BindingKey> getDependencies() {
    return dependencies;
  }

  public TypeElement getSourceClassElement() {
    return sourceClassElement;
  }

  public CoreInjectorInfo getCoreInjectorInfo() { return coreInjectorInfo; }

  @Nullable
  public ExecutableElement getProvisionMethodElement() {
    return provisionMethodElement;
  }

  public ProvisionType getType() {
    return type;
  }

  public boolean isEitherComponentBuilder() {
    return sourceClassElement == null;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("type", type)
        .add("sourceType", dependencySourceType)
        .add("dependant", dependant)
        .add("dependencies", dependencies)
        .add("sourceClassElement", sourceClassElement)
        .add("provisionMethodElement", provisionMethodElement)
        .add("coreInjectorInfo", coreInjectorInfo)
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
    if (!(object instanceof DependencyInfo)) {
      return false;
    }
    DependencyInfo that = (DependencyInfo) object;
    return
        dependencySourceType == that.getDependencySourceType()
        && getDependant().equals(that.getDependant())
        && Objects.equal(getDependencies(), that.getDependencies())
        && Objects.equal(this.getSourceClassElement(), that.getSourceClassElement())
        && Objects.equal(this.getProvisionMethodElement(), that.getProvisionMethodElement())
        && this.getType().equals(that.getType())
        && Objects.equal(this.coreInjectorInfo, that.coreInjectorInfo);
  }

  public boolean isUnique() {
    return type.equals(UNIQUE);
  }

  public boolean isSet() {
    return type.equals(SET) || type.equals(SET_VALUES);
  }
  
  private boolean isMap() {
    return type.equals(MAP);
  }

  public boolean isMultiBinding() {
    return isSet() || isMap();
  }
}
