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

import com.google.common.collect.Sets;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.TypeName;

import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Key that identifies a binding. It is made up of a type and an optional qualifier.
 */
public class BindingKey {
  private final TypeName typeName;

  @Nullable private final AnnotationSpec qualifier;

  public static BindingKey get(TypeMirror type, @Nullable AnnotationMirror qualifier) {
    Preconditions.checkNotNull(type);
    TypeKind typeKind = type.getKind();
    if (typeKind.equals(TypeKind.ERROR)) {
      throw new ResolveTypeMirrorException(type);
    }
    Preconditions.checkArgument(
        typeKind.equals(TypeKind.DECLARED)
            || typeKind.isPrimitive()
            || typeKind.equals(TypeKind.TYPEVAR)
            || typeKind.equals(TypeKind.ARRAY),
        String.format("Unexpected type %s of Kind %s", type, typeKind));

    return new BindingKey(type, qualifier);
  }

  public static BindingKey get(TypeMirror type) {
    return new BindingKey(type, (AnnotationSpec) null);
  }

  public static BindingKey get(TypeName typeName) {
    return new BindingKey(typeName, (AnnotationMirror) null);
  }

  public static BindingKey get(TypeName typeName, @Nullable AnnotationMirror qualifier) {
    return new BindingKey(typeName, qualifier);
  }

  public static BindingKey get(TypeName typeName, @Nullable AnnotationSpec qualifier) {
    return new BindingKey(typeName, qualifier);
  }

  public static BindingKey get(Element element) {
    ElementKind elementKind = element.getKind();
    Preconditions.checkArgument(
        Sets.newHashSet(
                ElementKind.CLASS,
                ElementKind.PARAMETER,
                ElementKind.INTERFACE,
                ElementKind.FIELD)
            .contains(elementKind)

        /* TODO: restore this. && (Utils.isEitherComponentBuilder(element)
        || Utils.isEitherComponent(element))*/ ,
        String.format("Unexpected element %s of Kind %s", element, elementKind));
    return get(element.asType(), Utils.getQualifier(element));
  }

  public static BindingKey get(TypeMirror typeMirror, @Nullable AnnotationSpec qualifier) {
    return new BindingKey(typeMirror, qualifier);
  }

  private BindingKey(TypeMirror type, @Nullable AnnotationMirror qualifier) {
    this(Utils.getTypeName(type), qualifier);
  }

  private BindingKey(TypeMirror type, @Nullable AnnotationSpec qualifier) {
    this(Utils.getTypeName(type), qualifier);
  }

  private BindingKey(TypeName typeName, @Nullable AnnotationMirror qualifier) {
    this(typeName, qualifier == null ? null : AnnotationSpec.get(qualifier));
  }

  private BindingKey(TypeName typeName, @Nullable AnnotationSpec qualifier) {
    this.typeName = Preconditions.checkNotNull(typeName);
    this.qualifier = qualifier;
  }

  private BindingKey(DeclaredType type) {
    this(type, (AnnotationSpec) null);
  }

  public TypeName getTypeName() {
    return typeName;
  }

  @Nullable
  public AnnotationSpec getQualifier() {
    return qualifier;
  }

  public BindingKey boxOrUnbox() {
    Preconditions.checkArgument(typeName.isPrimitive() || typeName.isBoxedPrimitive());
    if (typeName.isPrimitive()) {
      return BindingKey.get(typeName.box(), qualifier);
    } else {
      return BindingKey.get(typeName.unbox(), qualifier);
    }

  }

  @Override
  public boolean equals(Object that) {
    if (this == that) {
      return true;
    }

    if (!(that instanceof BindingKey)) {
      return false;
    }

    BindingKey other = (BindingKey) that;
    return getTypeName().equals(other.getTypeName()) && Objects.equal(qualifier, other.qualifier);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(typeName, qualifier);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("typeName", typeName)
        .add("qualifier", qualifier)
        .toString();
  }
}
