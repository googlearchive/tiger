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
public class NewBindingKey {
  private final TypeName typeName;

  @Nullable private final AnnotationSpec qualifier;

  public static NewBindingKey get(TypeMirror type, @Nullable AnnotationMirror qualifier) {
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

    return new NewBindingKey(type, qualifier);
  }

  public static NewBindingKey get(TypeMirror type) {
    return new NewBindingKey(type, (AnnotationSpec) null);
  }

  public static NewBindingKey get(TypeName typeName) {
    return new NewBindingKey(typeName, (AnnotationMirror) null);
  }

  public static NewBindingKey get(TypeName typeName, @Nullable AnnotationMirror qualifier) {
    return new NewBindingKey(typeName, qualifier);
  }

  public static NewBindingKey get(TypeName typeName, @Nullable AnnotationSpec qualifier) {
    return new NewBindingKey(typeName, qualifier);
  }

  public static NewBindingKey get(Element element) {
    ElementKind elementKind = element.getKind();
    Preconditions.checkArgument(
        elementKind.equals(ElementKind.CLASS) || elementKind.equals(ElementKind.PARAMETER),
        String.format("Unexpected element %s of Kind %s", element, elementKind));
    return get(element.asType(), Utils.getQualifier(element));
  }

  public static NewBindingKey get(TypeMirror typeMirror, @Nullable AnnotationSpec qualifier) {
    return new NewBindingKey(typeMirror, qualifier);
  }

  private NewBindingKey(TypeMirror type, @Nullable AnnotationMirror qualifier) {
    this(Utils.getTypeName(type), qualifier);
  }

  private NewBindingKey(TypeMirror type, @Nullable AnnotationSpec qualifier) {
    this(Utils.getTypeName(type), qualifier);
  }

  private NewBindingKey(TypeName typeName, @Nullable AnnotationMirror qualifier) {
    this(typeName, qualifier == null ? null : AnnotationSpec.get(qualifier));
  }

  private NewBindingKey(TypeName typeName, @Nullable AnnotationSpec qualifier) {
    this.typeName = Preconditions.checkNotNull(typeName);
    this.qualifier = qualifier;
  }

  private NewBindingKey(DeclaredType type) {
    this(type, (AnnotationSpec) null);
  }

  public TypeName getTypeName() {
    return typeName;
  }

  @Nullable
  public AnnotationSpec getQualifier() {
    return qualifier;
  }

  @Override
  public boolean equals(Object that) {
    if (this == that) {
      return true;
    }

    if (!(that instanceof NewBindingKey)) {
      return false;
    }

    NewBindingKey other = (NewBindingKey) that;
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
