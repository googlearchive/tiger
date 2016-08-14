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

import static com.google.auto.common.MoreElements.isAnnotationPresent;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;

import dagger.Lazy;
import dagger.MapKey;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Scope;
import javax.inject.Singleton;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * Misc utilities to help reusing codes.
 */
class Utils {

  private static final String PACKAGED_INJECTOR_NAME = "PackagedInjector";
  private static final String MULTI_BINDING_INJECTOR_NAME = "MultiBindingInjector";
  private static final Logger LOGGER = Logger.getLogger("Utils");

  static {
    LOGGER.setLevel(Level.SEVERE);
  }

  public static boolean isSingletonScoped(Elements elementUtils, Element element) {
    DeclaredType scopeType = Utils.getScopeType(element);
    boolean result =
        scopeType != null
            && scopeType
                .asElement()
                .equals(elementUtils.getTypeElement(Singleton.class.getCanonicalName()));
    return result;
  }

  /**
   * Return the binding qualifier of the given {@link Element}, null if none.
   */
  @Nullable
  public static AnnotationMirror getQualifier(Element element) {
    return getAnnotationMirrorWithMetaAnnotation(element, Qualifier.class);
  }

  /**
   * Return the binding qualifier of the given {@link Element}, null if none.
   */
  @Nullable
  public static AnnotationMirror getMapKey(Element element) {
    return getAnnotationMirrorWithMetaAnnotation(element, MapKey.class);
  }

  /**
   * Return the annotation with given metaAnnotation, null if none.
   */
  @Nullable
  public static AnnotationMirror getAnnotationMirrorWithMetaAnnotation(Element element,
      Class<? extends Annotation> metaAnnotation) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      if (annotationType.asElement().getAnnotation(metaAnnotation) != null) {
        return annotationMirror;
      }
    }
    return null;
  }

  /**
   * Returns value for the key in the given annotation, null if key does not exist.
   */
  @Nullable
  public static AnnotationValue getAnnotationValue(AnnotationMirror annotationMirror, String key) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
        : annotationMirror.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(key)) {
        return entry.getValue();
      }
    }
    return null;
  }
  
  /**
   * Returns type for the key in the given annotation, null if key does not exist.
   */
  @Nullable
  public static TypeMirror getAnnotationValueType(AnnotationMirror annotationMirror, String key) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
        : annotationMirror.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(key)) {
        return entry.getKey().getReturnType();
      }
    }
    return null;
  }
  
  /**
   * Returns type mirror of given element of the given mirror, null if the element does not exist.
   */
  @Nullable
  public static TypeMirror getElementTypeMirror(AnnotationMirror annotationMirror, String key) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
        : annotationMirror.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(key)) {
        return entry.getKey().getReturnType();
      }
    }
    return null;
  }

  /**
   * Returns specified annotation, null if not exist.
   */
  @Nullable
  public static AnnotationMirror getAnnotationMirror(
      Element element, Class<? extends Annotation> annotationClass) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      if (((TypeElement) annotationType.asElement())
          .getQualifiedName().contentEquals(annotationClass.getCanonicalName())) {
        return annotationMirror;
      }
    }
    return null;
  }

  /**
   * Return the binding qualifier of the given {@link Element}, null if none.
   */
  @Nullable
  public static DeclaredType getScopeType(Element element) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      if (isScopeTypeElement((TypeElement) annotationType.asElement())) {
        return annotationType;
      }
    }
    return null;
  }

  /**
   * Return if the given {@link TypeElement} is annotated with {@link Scope}.
   */
  public static boolean isScopeTypeElement(TypeElement element) {
    return element.getAnnotation(Scope.class) != null;
  }

  public static boolean isProvidesMethod(Element element, ProcessingEnvironment env) {
    return element.getKind().equals(ElementKind.METHOD)
        && hasAnnotationMirror(element, Provides.class, env);
  }

  /**
   * Returns specified annotation, null does not exist.
   */
  public static boolean hasAnnotationMirror(
      Element element, Class<? extends Annotation> annotation, ProcessingEnvironment env) {
    Elements elements = env.getElementUtils();
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      if (annotationMirror
          .getAnnotationType()
          .asElement()
          .equals(elements.getTypeElement(annotation.getCanonicalName()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns true if the given type can be bound to some type.
   */
  public static boolean isBindableType(TypeMirror type) {
    boolean result;
    if (type.getKind().isPrimitive()) {
      result = true;
    } else {
      TypeKind typeKind = type.getKind();
      switch (typeKind) {
        case DECLARED:
          DeclaredType declaredType = (DeclaredType) type;
          List<? extends TypeMirror> args = declaredType.getTypeArguments();
          result = true;
          for (TypeMirror argumentType : args) {
            if (!isBindableType(argumentType)) {
              result = false;
              break;
            }
          }
          break;
        case WILDCARD:
          result = true;
          break;
        default:
          result = false;
      }
    }
    LOGGER.log(Level.INFO,
        String.format("isBindableType: %s : %s : %s", type, type.getKind(), result));
    return result;
  }

  public static NewBindingKey getKeyProvidedByMethod(ExecutableElement method) {
    return NewBindingKey.get(method.getReturnType(), getQualifier(method));
  }
  
  /**
   * Return {@link NewDependencyInfo} for the generalized {@link NewBindingKey} for
   * the give key. Null if not applicable or not exist.
   */
  public static NewDependencyInfo getDependencyInfoByGeneric(
      SetMultimap<NewBindingKey, NewDependencyInfo> dependencies, NewBindingKey key) {
    TypeName typeName = key.getTypeName();
    Preconditions.checkArgument(
        key.getQualifier() == null,
        String.format(
            "Binding to %s is supposed to be resolved through generic type of %s"
                + "but has non-null qualifier.",
            key,
            typeName));
    if (typeName instanceof ParameterizedTypeName) {
      ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
      ClassName rawTypeName = parameterizedTypeName.rawType;
      NewBindingKey rawKey = NewBindingKey.get(rawTypeName);
      if (dependencies.containsKey(rawKey)) {
        NewDependencyInfo dependencyInfo = Iterables.getOnlyElement(dependencies.get(rawKey));
        TypeName formalTypeName = dependencyInfo.getDependant().getTypeName();
        Preconditions.checkState(
            formalTypeName instanceof ParameterizedTypeName,
            String.format(
                "Formal type %s is not of type ParameterizedTypeName. Related actual type is %s",
                formalTypeName,
                parameterizedTypeName));

        Map<TypeVariableName, TypeName> mapTypeVariableToSpecialized =
            getMapFromTypeVariableToSpecialized(
                parameterizedTypeName, (ParameterizedTypeName) formalTypeName);
        Set<NewBindingKey> specializedDependencies =
            specializeIfNeeded(dependencyInfo.getDependencies(), mapTypeVariableToSpecialized);
        return new NewDependencyInfo(
            key,
            specializedDependencies,
            dependencyInfo.getSourceClassElement(),
            dependencyInfo.getProvisionMethodElement(),
            dependencyInfo.getType());
      }
    }
    return null;
  }

  public static Map<TypeVariableName, TypeName> getMapFromTypeVariableToSpecialized(
      ParameterizedTypeName actual, ParameterizedTypeName formal) {
    Preconditions.checkArgument(
        formal.typeArguments.size() == actual.typeArguments.size(),
        String.format("Incompatible actual type %s and formal type %s.", actual, formal));
    Preconditions.checkArgument(
        !formal.typeArguments.isEmpty(),
        String.format("formal type %s and actual type %s has no argument.", formal, actual));

    Map<TypeVariableName, TypeName> result = new HashMap<>();
    for (int i = 0; i < formal.typeArguments.size(); i++) {
      Preconditions.checkArgument(
          formal.typeArguments.get(i) instanceof TypeVariableName,
          String.format("formal type %s has non-TypeVariableName parameter.", formal));
      Preconditions.checkArgument(
          !(actual.typeArguments.get(i) instanceof TypeVariableName),
          String.format("actual type %s has TypeVariableName parameter.", actual));
      result.put(((TypeVariableName) formal.typeArguments.get(i)), actual.typeArguments.get(i));
    }

    return result;
  }

  public static Set<NewBindingKey> specializeIfNeeded(
      Set<NewBindingKey> keys, Map<TypeVariableName, TypeName> map) {
    Set<NewBindingKey> result = new HashSet<>();
    for (NewBindingKey k : keys) {
      result.add(specializeIfNeeded(k, map));
    }

    return result;
  }

  /**
   * Returns a {@link TypeName} with TypeVariable replaced by specialType if
   * applicable.
   */
  public static NewBindingKey specializeIfNeeded(
      NewBindingKey dependencyKey, Map<TypeVariableName, TypeName> map) {
    AnnotationSpec qualifier = dependencyKey.getQualifier();
    TypeName typeName = specializeIfNeeded(dependencyKey.getTypeName(), map);
    return NewBindingKey.get(typeName, qualifier);
  }

  /**
   * Returns true if the given type can be bound to some type. Note: this should
   * not be used with raw type of generic type.
   */
  public static boolean isBindable(TypeName typeName) {
    if (typeName instanceof ParameterizedTypeName) {
      for (TypeName t : ((ParameterizedTypeName) typeName).typeArguments) {
        if (!isBindable(t)) {
          return false;
        }
      }
      return true;
    } else if (typeName instanceof ClassName) {
      return true;
    } else if (typeName instanceof WildcardTypeName) {
      return true;
    } else
      return typeName.isPrimitive();
  }

  public static PackageElement getPackage(TypeElement typeElement) {
    Element result = typeElement.getEnclosingElement();
    ElementKind elementKind = result.getKind();
    while (!elementKind.equals(ElementKind.PACKAGE)) {
      Preconditions.checkState(
          elementKind.isClass() || elementKind.isInterface(), String
              .format("Utils.getPackage: unexpected kind: %s for type: %s", elementKind,
                  typeElement));
      result = result.getEnclosingElement();
      elementKind = result.getKind();
    }
    return (PackageElement) result;
  }

  public static String getPackageString(TypeElement typeElement) {
    
    return getPackage(typeElement).getQualifiedName().toString();
  }

  public static List<NewBindingKey> getDependenciesFromExecutableElement(
      ExecutableElement executableElement) {
    List<NewBindingKey> keys = new ArrayList<>();
    for (VariableElement variableElement : executableElement.getParameters()) {
      keys.add(NewBindingKey.get(variableElement));
    }
    return keys;
  }

  public static String getQualifiedName(TypeElement typeElement) {
    return typeElement.getQualifiedName().toString();
  }

  public static String getGetMethodName(TypeElement cls) {
    return getGetMethodName(ClassName.get(cls));
  }

  public static String getCanonicalName(ClassName className) {
    Joiner joiner = Joiner.on(".");
    return joiner.join(
        Lists.asList(className.packageName(), className.simpleNames().toArray(new String[0])));
  }

  public static List<VariableElement> getInjectedFields(
      TypeElement cls, ProcessingEnvironment env) {
    List<VariableElement> result = new ArrayList<>();
    for (Element element : cls.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.FIELD) && isInjected(element, env)) {
        result.add((VariableElement) element);
      }
    }
    return result;
  }

  public static List<ExecutableElement> getInjectedMethods(
      TypeElement cls, ProcessingEnvironment env) {
    List<ExecutableElement> result = new ArrayList<>();
    for (Element element : cls.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.METHOD) && isInjected(element, env)) {
        result.add((ExecutableElement) element);
      }
    }
    return result;
  }

  public static boolean hasInjectedFieldsOrMethods(TypeElement cls, ProcessingEnvironment env) {
    return !getInjectedFields(cls, env).isEmpty() || !getInjectedMethods(cls, env).isEmpty();
  }

  public static boolean isInjectionMethod(Element element) {
    if (!element.getKind().equals(ElementKind.METHOD)) {
      return false;
    }
    ExecutableElement executableElement = (ExecutableElement) element;
    return executableElement.getReturnType().getKind().equals(TypeKind.VOID)
        && (executableElement.getParameters().size() == 1);
  }

  public static boolean isProvisionMethodInInjector(Element element) {
    if (!element.getKind().equals(ElementKind.METHOD)) {
      return false;
    }
    ExecutableElement executableElement = (ExecutableElement) element;
    return !executableElement.getReturnType().getKind().equals(TypeKind.VOID)
        && (executableElement.getParameters().size() == 0);
  }

  public static boolean hasAnonymousParentClass(TypeElement cls) {
    Preconditions.checkNotNull(cls);
    do {
      if (cls.getSimpleName().length() == 0) {
        return true;
      }
      TypeMirror parentClass = cls.getSuperclass();
      if (parentClass.getKind().equals(TypeKind.NONE)) {
        return false;
      }

      cls = (TypeElement) ((DeclaredType) parentClass).asElement();
    } while (true);
  }

  /**
   * Returns the injected ctor, null if none.
   */
  public static ExecutableElement findInjectedCtor(TypeElement cls, ProcessingEnvironment env) {
    for (Element element : cls.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.CONSTRUCTOR) && isInjected(element, env)) {
        return (ExecutableElement) element;
      }
    }
    return null;
  }

  /**
   * Returns whether the given {@link AccessibleObject} is injected.
   */
  public static boolean isInjected(Element element, ProcessingEnvironment env) {
    return hasAnnotationMirror(element, Inject.class, env);
  }

  public static TypeName getTypeName(TypeMirror typeMirror) throws ResolveTypeMirrorException {
    try {
      return TypeName.get(typeMirror);
    } catch (Exception e) {
      throw new ResolveTypeMirrorException(typeMirror);
    }
  }

  /**
   * Returns if the key has built-in binding.
   */
  public static boolean hasBuiltinBinding(NewBindingKey key) {
    return hasBuiltinBinding(key.getTypeName());
  }

  public static boolean hasBuiltinBinding(TypeName type) {
    if (! (type instanceof ParameterizedTypeName)) {
      return false;
    }
    ParameterizedTypeName typeName = (ParameterizedTypeName) type;

    ClassName rawType = typeName.rawType;
    return rawType.equals(ClassName.get(Provider.class))
        || rawType.equals(ClassName.get(Lazy.class));
  }


  /**
   * Return null means the give key is not bound, which is an error. We cannot
   * return empty Set in this case because that means the binding exists and
   * depends on nothing.
   */
  @Nullable
  public static Set<NewDependencyInfo> getDependencyInfo(
      SetMultimap<NewBindingKey, NewDependencyInfo> dependencies, NewBindingKey key) {
    if (dependencies.containsKey(key)) {
      return dependencies.get(key);
    } else if (hasBuiltinBinding(key)) {
      return getDependencyInfo(dependencies, getElementKeyForBuiltinBinding(key));
    } else if (isMap(key)) {
      // Handle the case that value is dagger built-in type.
      NewBindingKey peeledKey = peelMapWithBuiltinValue(key);
      if (peeledKey != null) {
        return getDependencyInfo(dependencies, peeledKey);
      } else {
        return null;
      }
    } else {
      NewDependencyInfo dependencyInfo = getDependencyInfoByGeneric(dependencies, key);
      if (dependencyInfo == null) {
        return null;
      } else {
        return Sets.newHashSet(dependencyInfo);
      }
    }
  }

  /**
   * If the key comes with value of type that has dagger builtin binding, return one
   * with the type replaced by the element of the original value type, null otherwise.
   * Nested built-in binding like Lazy<Lazy<Foo>>, Provider<Lazy<Foo>>, etc, are not 
   * supported.
   */
  @Nullable
  public static NewBindingKey peelMapWithBuiltinValue(NewBindingKey key) {
    Preconditions.checkState(isMap(key), String.format("Expect a map but got %s", key));
    ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) key.getTypeName();
    TypeName valueType = parameterizedTypeName.typeArguments.get(1);
    if (hasBuiltinBinding(valueType)) {
      TypeName mapKeyType = parameterizedTypeName.typeArguments.get(0);
      TypeName elementType =
          Iterables.getOnlyElement(((ParameterizedTypeName) valueType).typeArguments);
      TypeName newType =
          ParameterizedTypeName.get(ClassName.get(Map.class), mapKeyType, elementType);
      return NewBindingKey.get(newType, key.getQualifier());
    }
    
    return null;
  }

  public static boolean isMapWithBuiltinValueType(NewBindingKey key) {
    return isMap(key) && peelMapWithBuiltinValue(key) != null;
  }
  
  /**
   * Returns if key is a map with type variables.
   */
  public static boolean isMap(NewBindingKey key) {
    TypeName typeName = key.getTypeName();
    if (!(typeName instanceof ParameterizedTypeName)) {
      return false;
    }
    ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
    return parameterizedTypeName.rawType.equals(ClassName.get(Map.class));
  }

  /**
   * Return {@link NewBindingKey} for element of the give {@link NewBindingKey} that
   * has built-in binding, null if not built-in building.
   */
  @Nullable
  public static NewBindingKey getElementKeyForBuiltinBinding(NewBindingKey key) {
    if (!hasBuiltinBinding(key)) {
      return null;
    }
    ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) key.getTypeName();

    TypeName typeName = Iterables.getOnlyElement(parameterizedTypeName.typeArguments);
    AnnotationSpec qualifier = key.getQualifier();
    return NewBindingKey.get(typeName, qualifier);
  }

  /**
   * Changes give tree to {@link SetMultimap} from parent to children.
   */
  public static <T> SetMultimap<T, T> reverseTree(Map<T, T> childToParentMap) {
    SetMultimap<T, T> parentToChildrenMap = HashMultimap.create();
    for (Map.Entry<T, T> entry : childToParentMap.entrySet()) {
      parentToChildrenMap.put(entry.getValue(), entry.getKey());
    }
    return parentToChildrenMap;
  }

  /**
   * Returns width-first ordered component from the give map.
   */
  public static <T> List<T> getOrderedScopes(Map<T, T> childToParentMap) {
    List<T> result = new ArrayList<>();
    SetMultimap<T, T> parentToChildrenMap = reverseTree(childToParentMap);
    T rootElement =
        Iterables.getOnlyElement(
            Sets.difference(Sets.newHashSet(childToParentMap.values()), childToParentMap.keySet()));
    result.add(rootElement);
    for (int i = 0; i < result.size(); i++) {
      result.addAll(parentToChildrenMap.get(result.get(i)));
    }
    return result;
  }
  
  public static Set<TypeElement> getNonNullaryCtorOnes(Set<TypeElement> elements) {
    Set<TypeElement> result = new HashSet<>();
    for (TypeElement typeElement : elements) {
      boolean hasCtor = false;
      boolean hasNullaryCtor = false;
      for (Element element : typeElement.getEnclosedElements()) {
        if (element.getKind().equals(ElementKind.CONSTRUCTOR)) {
          hasCtor = true;
          ExecutableElement ctor = (ExecutableElement) element;
          if (ctor.getParameters().isEmpty()) {
            hasNullaryCtor = true;
            break;
          }
        }
      }
      if (!hasNullaryCtor && hasCtor) {
        result.add(typeElement);
      }
    }
    return result;
  }

  /**
   * Returns if the moduleType has provision methods.
   */
  public static boolean hasProvisionMethod(DeclaredType moduleType) {
    TypeElement moduleElement = (TypeElement) moduleType.asElement();
    Preconditions.checkArgument(
        moduleElement.getAnnotation(Module.class) != null,
        String.format("not module: %s.", moduleType));
    for (Element element : moduleElement.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.METHOD)
          && (element.getAnnotation(Provides.class) != null)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the scope of this module, null for unscoped modules. Dagger requires that each module
   * can only contain no more than one scope type of scoped binding. Unscoped bindings are not
   * limited.
   * TODO(freeman): supported included modules.
   */
  @Nullable
  public static TypeElement getModuleScope(DeclaredType moduleType) {
    TypeElement moduleElement = (TypeElement) moduleType.asElement();
    Preconditions.checkArgument(
        moduleElement.getAnnotation(Module.class) != null,
        String.format("not module: %s.", moduleType));
    for (Element element : moduleElement.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.METHOD)
          && (element.getAnnotation(Provides.class) != null)) {
        for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
          Annotation scope =
              annotationMirror.getAnnotationType().asElement().getAnnotation(Scope.class);
          if (scope != null) {
            return (TypeElement) annotationMirror.getAnnotationType().asElement();
          }
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  public static Set<TypeElement> findAllModulesRecursively(TypeElement inModule) {
    Set<TypeElement> result = new HashSet<>();
  
    result.add(inModule);
  
    for (AnnotationMirror annotationMirror : inModule.getAnnotationMirrors()) {
      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
          annotationMirror.getElementValues().entrySet()) {
        ExecutableElement key = entry.getKey();
  
        /** Checks for {@link Module.includes}. */
        if (key.getSimpleName().contentEquals("includes")) {
  
          for (AnnotationValue annotationValue :
              (List<AnnotationValue>) entry.getValue().getValue()) {
            TypeElement childModule =
                (TypeElement) ((DeclaredType) annotationValue.getValue()).asElement();
            result.addAll(findAllModulesRecursively(childModule));
          }
        }
      }
    }
  
    return result;
  }

  public static Set<TypeElement> findAllModulesRecursively(Collection<TypeElement> inModules) {
    Set<TypeElement> result = new HashSet<>();
  
    for (TypeElement module : inModules) {
      result.addAll(findAllModulesRecursively(module));
    }
  
    return result;
  }

  public static List<TypeElement> sortByFullName(Collection<TypeElement> typeElements) {
    Ordering<TypeElement> ordering = new Ordering<TypeElement>() {
      @Override
      public int compare(TypeElement left, TypeElement right) {
        return left.getQualifiedName().toString().compareTo(right.getQualifiedName().toString());
      }
    };
    return ordering.immutableSortedCopy(typeElements);
  }

  /**
   * Returns "com_Foo" for com.Foo, "com_Foo_com_Bar_com_Baz" for Foo<Bar, Baz>.
   * upper_bounds_UpperBound_Foo for "? extends Foo" and
   * lower_bounds_LowerBound_Foo for "? super Foo". Assuming raw types are not
   * used, there will be not conflicts.
   */
  public static String getSourceCodeName(TypeName typeName) {
    Preconditions.checkNotNull(typeName);
  
    if (typeName.isPrimitive()) {
      return typeName.toString();
    } else if (typeName instanceof ClassName) {
      return getClassCanonicalName((ClassName) typeName).replace(".", "_");
    } else if (typeName instanceof ParameterizedTypeName) {
      ParameterizedTypeName p = (ParameterizedTypeName) typeName;
      StringBuilder builder = new StringBuilder(getSourceCodeName(p.rawType));
      for (TypeName t : p.typeArguments) {
        builder.append("_").append(getSourceCodeName(t));
      }
      return builder.toString();
    } else if (typeName instanceof WildcardTypeName) {
      WildcardTypeName w = (WildcardTypeName) typeName;
      if (w.upperBounds.size() > 0) {
        return "upper_bounds_" + getSourceCodeName(w.upperBounds.get(0));
      } else {
        Preconditions.checkState(w.lowerBounds.size() > 0);
        return "lower_bounds_" + getSourceCodeName(w.lowerBounds.get(0));
      }
    } else if (typeName instanceof ArrayTypeName) {
      ArrayTypeName arrayTypeName = (ArrayTypeName) typeName;
      return "ArrayOf" + getSourceCodeName(arrayTypeName.componentType);
    }
    throw new RuntimeException("Unexpected type: " + typeName);
  }

  public static String getSourceCodeName(TypeElement typeElement) {
    return getSourceCodeName(TypeName.get(typeElement.asType()));
  }

  /**
   * Adds set method for type to builder. 
   */
  public static void addSetMethod(ClassName builderParentClassName, TypeSpec.Builder builder,
      ClassName type) {
    String fullName = getSourceCodeName(type);
    String simpleName = type.simpleName();
    String methodName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    builder.addField(type, fullName, Modifier.PRIVATE);
    String argName = "arg";
    MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
        .addModifiers(Modifier.PUBLIC).addParameter(type, argName)
        .returns(ClassName.get(builderParentClassName.packageName(),
            builderParentClassName.simpleName(), "Builder"))
        .addCode("this.$N = $N;", fullName, argName).addCode("return this;");
    builder.addMethod(methodBuilder.build());
  }
  
  public static <K, V> SetMultimap<V, K> reverseSetMultimap(SetMultimap<K, V> map) {
    SetMultimap<V, K> result = HashMultimap.create();
    for (Map.Entry<K, V> entry : map.entries()) {
      result.put(entry.getValue(), entry.getKey());
    }
    return result;
  }
  
  public static boolean isTypeElementEqual(TypeElement a, TypeElement b) {
    return a.getQualifiedName().contentEquals(b.getQualifiedName());
  }

  /**
   * Returns whether the give class is a Dagger {@link Module}.
   */
  public static boolean isModule(Class<?> clazz) {
    return clazz.getAnnotation(Module.class) != null;
  }

  /**
   * Returns whether the given {@link AccessibleObject} is a {@link Provides}
   * one.
   */
  public static <T extends AccessibleObject> boolean isProvides(T t) {
    return t.getAnnotation(Provides.class) != null;
  }

  /**
   * Returns whether the given annotation is a {@link Qualifier}.
   */
  public static boolean isQualifierAnnotation(Annotation annotation) {
    return annotation.annotationType().getAnnotation(Qualifier.class) != null;
  }

  /**
   * Returns qualifier annotation for the given method, null if none.
   */
  public static Annotation getQualifierAnnotation(AccessibleObject accessibleObject) {
    for (Annotation annotation : accessibleObject.getAnnotations()) {
      if (isQualifierAnnotation(annotation)) {
        return annotation;
      }
    }

    return null;
  }

  /**
   * Returns qualifier annotation for the given method, null if none.
   */
  @Nullable
  public static DeclaredType getQualifierAnnotation(Element element) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      if (annotationType.asElement().getAnnotation(Qualifier.class) != null) {
        return annotationType;
      }
    }

    return null;
  }

  /**
   * Returns the injected ctor, null if none.
   */
  public static Constructor<?> findInjectedCtor(Class<?> clazz) {
    Constructor<?>[] ctors = clazz.getDeclaredConstructors();
    for (Constructor<?> ctor : ctors) {
      if (isInjected(ctor)) {
        return ctor;
      }
    }

    return null;
  }

  /**
   * Returns whether the given {@link AccessibleObject} is injected.
   */
  public static boolean isInjected(AccessibleObject accessibleObject) {
    Annotation[] annotations = accessibleObject.getAnnotations();
    for (Annotation annotation : annotations) {
      Class<?> type = annotation.annotationType();
      if (type.getName().equals(Inject.class.getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns included {@link Module}s of the specified {@link Module}
   * recursively.
   */
  private static List<Class<?>> getIncludedModules(Class<?> module) {
    List<Class<?>> result = new ArrayList<>();
    Module childModule = module.getAnnotation(Module.class);
    Class<?>[] includes = childModule.includes();
    result.addAll(Lists.newArrayList(includes));
    for (Class<?> clazz : includes) {
      LOGGER.log(Level.INFO, "module: " + module + " child: " + clazz);
      result.addAll(getIncludedModules(clazz));
    }
    return result;
  }

  /**
   * Returns {@link Module}s referenced by the given {@link Module}s
   * recursively.
   */
  public static Set<Class<?>> findAllModules(Set<Class<?>> modules) {
    Set<Class<?>> result = new HashSet<>();

    for (Class<?> module : modules) {
      result.addAll(getIncludedModules(module));
    }

    result.addAll(modules);
    return result;
  }

  public static boolean hasInjectedFieldsOrMethods(Class<?> clazz) {
    return !getInjectedFields(clazz).isEmpty() || !getInjectedMethods(clazz).isEmpty();
  }

  public static List<Field> getInjectedFields(Class<?> clazz) {
    return getInjected(Arrays.asList(clazz.getDeclaredFields()));
  }

  public static List<Method> getInjectedMethods(Class<?> clazz) {
    return getInjected(Arrays.asList(clazz.getDeclaredMethods()));
  }

  public static List<Method> getProvisionMethods(Class<?> clazz) {
    return filterProvides(Arrays.asList(clazz.getDeclaredMethods()));
  }

  private static <T extends AccessibleObject> List<T> getInjected(Iterable<T> all) {
    List<T> result = new ArrayList<>();
    for (T t : all) {
      if (Utils.isInjected(t)) {
        result.add(t);
      }
    }

    return result;
  }

  private static <T extends AccessibleObject> List<T> filterProvides(Iterable<T> all) {
    List<T> result = new ArrayList<>();
    for (T t : all) {
      if (isProvides(t)) {
        result.add(t);
      }
    }

    return result;
  }

  /**
   * Returns a string with the first char lowered if it is in upper case, other
   * return the original one.
   */
  public static String lowerFirst(String s) {
    if (s.isEmpty()) {
      return s;
    }

    char c = s.charAt(0);
    if (Character.isLowerCase(c)) {
      return s;
    }

    c = Character.toLowerCase(c);
    return c + s.substring(1);
  }

  private static Annotation[] getParameterQualifierAnnotations(Annotation[][] annotationArrays) {
    Annotation[] result = new Annotation[annotationArrays.length];
    for (int i = 0; i < annotationArrays.length; i++) {
      Annotation[] annotations = annotationArrays[i];
      boolean found = false;
      for (Annotation annotation : annotations) {
        if (isQualifierAnnotation(annotation)) {
          result[i] = annotation;
          found = true;
          break;
        }
      }
      if (!found) {
        result[i] = null;
      }
    }

    return result;
  }

  private static Annotation[] getParameterQualifierAnnotations(Constructor<?> ctor) {
    Annotation[][] annotationArrays = ctor.getParameterAnnotations();
    return getParameterQualifierAnnotations(annotationArrays);
  }

  /*
     * Return an array including the qualifier annotation for each parameter, null
     * if no qualifier for the parameter. Size of returned array is same as the
     * number of parameters.
     */
  private static Annotation[] getParameterQualifierAnnotations(Method method) {
    Annotation[][] annotationArrays = method.getParameterAnnotations();
    return getParameterQualifierAnnotations(annotationArrays);
  }

  public static Provides.Type getProvidesType(Method method) {
    if (method.getAnnotation(IntoSet.class) != null) {
      return Provides.Type.SET;
    } else if (method.getAnnotation(ElementsIntoSet.class) != null) {
      return Provides.Type.SET_VALUES;
    } else if (method.getAnnotation(IntoMap.class) != null) {
      return Provides.Type.MAP;
    }
    return method.getAnnotation(Provides.class).type();
  }

  public static Provides.Type getProvidesType(ExecutableElement method) {
    if (isAnnotationPresent(method, IntoMap.class)) {
      return Provides.Type.MAP;
    } else if (isAnnotationPresent(method, IntoSet.class)) {
      return Provides.Type.SET;
    } else if (isAnnotationPresent(method, ElementsIntoSet.class)) {
      return Provides.Type.SET_VALUES;
    }
    return method.getAnnotation(Provides.class).type();
  }

  public static String getClassBinaryName(ClassName className) {
    StringBuilder builder = new StringBuilder(className.packageName()).append(".");

    for (String name : className.simpleNames()) {
      builder.append(name).append("$");
    }
    builder.delete(builder.length() - 1, builder.length());

    return builder.toString();
  }

  public static String getClassCanonicalName(ClassName className) {
    return getClassBinaryName(className).replace("$", ".");
  }

  public static boolean hasAnonymousParentClass(Class<?> cls) {
    Preconditions.checkNotNull(cls);
    try {
      do {
        if (cls.isAnonymousClass()) {
          return true;
        }
        cls = cls.getEnclosingClass();
      } while (cls != null);
      return false;
    } catch (IncompatibleClassChangeError e) {
      // We somehow run into this, ignore it.
      LOGGER.info(String.format("class: %s, exception: %s.", cls, e));
      return false;
    }
  }

  /**
   * Returns a {@link TypeName} with all the {@link TypeVariableName} in the
   * given typeName replaced with the {@link TypeName} found in map the the
   * {@link TypeVariableName} if necessary.
   */
  public static TypeName specializeIfNeeded(
      TypeName typeName, Map<TypeVariableName, TypeName> map) {
    if (typeName instanceof TypeVariableName) {
      Preconditions.checkArgument(map.containsKey(typeName));
      return map.get(typeName);
    } else if (typeName instanceof ParameterizedTypeName) {
      ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
      ClassName rawName = parameterizedTypeName.rawType;
      List<TypeName> parameterTypes = new ArrayList<>();
      for (TypeName t : parameterizedTypeName.typeArguments) {
        parameterTypes.add(specializeIfNeeded(t, map));
      }
      return ParameterizedTypeName.get(rawName, parameterTypes.toArray(new TypeName[0]));
    } else {
      return typeName;
    }
  }

  public static String getPackagedInjectorSimpleName(Class<? extends Annotation> scope) {
    return String.format(
        "%s%s",
        scope.getCanonicalName().replace(".", "_"),
        PACKAGED_INJECTOR_NAME);
  }

  public static String getPackagedInjectorSimpleName(TypeElement scope) {
    return String.format(
        "%s%s",
        scope.getQualifiedName().toString().replace(".", "_"),
        PACKAGED_INJECTOR_NAME);
  }

  public static String getMultiBindingInjectorSimpleName(Class<? extends Annotation> scope) {
    return String.format("%s%s", scope.getCanonicalName().replace(".", "_"),
        MULTI_BINDING_INJECTOR_NAME);
  }

  public static String getMultiBindingInjectorSimpleName(TypeElement scope) {
    return String.format("%s%s", scope.getQualifiedName().toString().replace(".", "_"),
        MULTI_BINDING_INJECTOR_NAME);
  }

  public static boolean isMultiBindingInjector(ClassName packagedInjectorClassName) {
    return packagedInjectorClassName
        .simpleName()
        .contains(MULTI_BINDING_INJECTOR_NAME);
  }

  public static String getGetMethodName(Class<?> cls) {
    return getGetMethodName(ClassName.get(cls));
  }

  public static String getGetMethodName(ClassName className) {
    return String.format(Locale.US, "get_%s", getClassCanonicalName(className).replace(".", "_"));
  }

  /**
   * Returns true if the method is a injection method, i.e., has single
   * parameter and returns void.
   */
  public static boolean isInjectionMethod(Method method) {
    return method.getReturnType().equals(Void.TYPE) && method.getParameterTypes().length == 1;
  }

}
