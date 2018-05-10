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
import static tiger.ProvisionType.MAP;
import static tiger.ProvisionType.SET;
import static tiger.ProvisionType.SET_VALUES;
import static tiger.ProvisionType.UNIQUE;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
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
import com.squareup.javapoet.TypeSpec.Builder;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import dagger.Binds;
import dagger.BindsInstance;
import dagger.BindsOptionalOf;
import dagger.Component;
import dagger.Lazy;
import dagger.MapKey;
import dagger.MembersInjector;
import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Generated;
import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
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
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * Misc utilities to help reusing codes.
 */
class Utils {

  private static final String TAG = "Utils";
  private static final String PACKAGED_INJECTOR_NAME = "PackagedInjector";
  private static final String MULTI_BINDING_INJECTOR_NAME = "MultiBindingInjector";
  private static final String COMPONANT_ANNOTATION_ELEMENT_DEPENDENCIES = "dependencies";

  /**
   * Using "_" makes file name beyond 255, which makes (linux) fs unhappy. "" will be screwed by
   * TrendingPlaceCollectionStreamItemViewModelImpl.Factory and
   * TrendingPlaceCollectionStreamItemViewModelImplFactory. We will use "_" but replace
   * "GenerciInjector" with "GI".
   */
  private static final String REPLACEMENT_FOR_INVALID_CHARS = "_";

  private final ProcessingEnvironment processingEnvironment;
  private final Messager messager;

  static HashSet<String> nameShouldBeKept =
      Sets.newHashSet(
          "FragmentComponent",
          "GmmActivityFragment",
          "HomeFragment",
          // "InitialGmmFragment",
          "IntentRegistrySubcomponent");
  static final HashSet<String> nameToTrim =
      Sets.newHashSet("Fragment",
          // "UgcPhotoActivityComponent",
          // "SimpleActivityComponent",
          "Subcomponent"
      );

  private final Elements elements;
  private final Types types;
  private final Logger logger;

  Utils(ProcessingEnvironment processingEnvironment,
      RoundEnvironment roundEnvironment) {
    this.processingEnvironment = processingEnvironment;
    elements = processingEnvironment.getElementUtils();
    messager = processingEnvironment.getMessager();
    types = processingEnvironment.getTypeUtils();
    logger = new Logger(messager, Kind.WARNING);
  }

  public void collectRequiredKeysFromClass(Set<BindingKey> result, TypeElement cls) {
    for (Element e : cls.getEnclosedElements()) {
      if (!isInjected(e)) {
        continue;
      }
      if (e.getKind().equals(ElementKind.FIELD)) {
        if (isNotSpecializedGeneric(TypeName.get(e.asType()))) {
          continue;
        }
        BindingKey key = BindingKey.get(e);
        result.add(key);
        // checkKey(key, e);
      } else {
        for (BindingKey key :
            getDependenciesFromMethod((ExecutableType) e.asType(), (ExecutableElement) e)) {
          if (isNotSpecializedGeneric(key)) {
            continue;
          }
          // checkKey(key, e);
          result.add(key);
        }
      }
    }
    TypeElement superCls = getSuper(cls);
    if (superCls != null) {
      collectRequiredKeysFromClass(result, superCls);
    }
  }

  public void checkKeys(Collection<BindingKey> keys, Element e) {
    for (BindingKey i : keys) {
      checkKey(i, e);
    }
  }

  private void checkKey(BindingKey key, Element e) {
    if (key.getTypeName().toString().contains("app.ApplicationComponent")) {
      logger.e("%s.%s provides ApplicationComponent", e.getEnclosingElement(), e);
    }
  }

  public boolean isSingletonScoped(Elements elementUtils, Element element) {
    DeclaredType scopeType = getScopeType(element, null);
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
  public AnnotationMirror getMapKey(Element element) {
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
   * Returns value for the key in the given annotation including default value, null if key does not exist.
   */
  @Nullable
  public static AnnotationValue getAnnotationValue(Elements elements,
      AnnotationMirror annotationMirror, String key) {
    // messager.printMessage(
    //     Kind.NOTE, TAG + ".getAnnotationValue: annotationMirror " + annotationMirror);
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
        : elements.getElementValuesWithDefaults(annotationMirror).entrySet()) {
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
  public TypeMirror getAnnotationValueType(AnnotationMirror annotationMirror, String key) {
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
  public TypeMirror getElementTypeMirror(AnnotationMirror annotationMirror, String key) {
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
  public AnnotationMirror getAnnotationMirror(
      Element element, Class<? extends Annotation> annotationClass) {
    return getAnnotationMirror(element, annotationClass.getCanonicalName());
  }

  /**
   * Returns specified annotation, null if not exist.
   */
  @Nullable
  public AnnotationMirror getAnnotationMirror(
      Element element, String annotation) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      if (((TypeElement) annotationType.asElement())
          .getQualifiedName().contentEquals(annotation)) {
        return annotationMirror;
      }
    }
    return null;
  }

  /** Return the first scope of the given {@link Element}, null if none. */
  @Nullable
  public DeclaredType getScopeType(Element element,
      @Nullable ScopeAliasCondenser scopeAliasCondenser) {
    Set<DeclaredType> scopes = getScopeTypes(element);
    DeclaredType scope = Iterables.getFirst(scopes, null);

    if (scope == null) {
      return null;
    }
    if (isReusableScope(scope)) {
      return null;
    }
    if (scopeAliasCondenser == null) {
      return scope;
    }
    return (DeclaredType)
          scopeAliasCondenser.getCoreScopeForAlias((TypeElement) scope.asElement()).asType();
  }

  public boolean isReusableScope(DeclaredType scope) {
    return scope.asElement().getSimpleName().contentEquals("Reusable");
  }

  /** Return the scopes of the given {@link Element}, empty set if none. */
  public Set<DeclaredType> getScopeTypes(Element element) {
    Set<DeclaredType> result = new HashSet<>();
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      if (isScopeTypeElement((TypeElement) annotationType.asElement())) {
        result.add(annotationType);
      }
    }
    return result;
  }

  /** Return the scope elements of the given {@link Element}, empty set if none. */
  @Nullable
  public static Set<TypeElement> getScopeTypeElements(Element element) {
    Set<DeclaredType> result = new HashSet<>();
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      DeclaredType annotationType = annotationMirror.getAnnotationType();
      if (isScopeTypeElement((TypeElement) annotationType.asElement())) {
        result.add(annotationType);
      }
    }
    return Sets.newHashSet(Collections2.transform(result, f -> (TypeElement) f.asElement()));
  }

  /**
   * Return if the given {@link TypeElement} is annotated with {@link Scope}.
   */
  public static boolean isScopeTypeElement(TypeElement element) {
    return element.getAnnotation(Scope.class) != null;
  }

  public boolean isProvisionMethodInModule(Element element) {
    return isProvidesMethod(element) || isBindsMethod(element) || isMultibindsMethod(element)
        || isBindsOptionalOfMethod(element);
  }

  public boolean isBindsOptionalOfMethod(Element element) {
    return element.getKind().equals(ElementKind.METHOD)
        && hasAnnotationMirror(element, BindsOptionalOf.class);  }

  /**
   * Returns whether the given element is a @Provides method.
   */
  public boolean isProvidesMethod(Element element) {
    return element.getKind().equals(ElementKind.METHOD)
        && hasAnnotationMirror(element, Provides.class);
  }

  /**
   * Returns specified annotation, null does not exist.
   */
  public boolean hasAnnotationMirror(
      Element element, Class<? extends Annotation> annotation) {
    return getAnnotationMirror(element, annotation) != null;
  }

  public boolean isEitherComponent(Element element) {
    return isComponent(element) || isSubcomponent(element);
  }

  public boolean isSubcomponent(Element element) {
    //System.out.println("xxx in isSubcompoonent element: " + element + " " + element.getKind());
    return isInterfaceOrAbstractClass(element)
        && hasAnnotationMirror(element, Subcomponent.class);
  }

  private static boolean isInterfaceOrAbstractClass(Element element) {
    return element.getKind().equals(ElementKind.INTERFACE)
        || element.getKind().equals(ElementKind.CLASS)
            && element.getModifiers().contains(Modifier.ABSTRACT);
  }

  public boolean isComponent(Element element) {
    return isInterfaceOrAbstractClass(element)
        && hasAnnotationMirror(element, Component.class);
  }

  public boolean isComponentBuilder(Element element) {
    return isInterfaceOrAbstractClass(element)
        && hasAnnotationMirror(element, Component.Builder.class);
  }

  public boolean isSubcomponentBuilder(Element element) {
    return isInterfaceOrAbstractClass(element)
        && hasAnnotationMirror(element, Subcomponent.Builder.class);
  }

  public boolean isEitherComponentBuilder(ProcessingEnvironment env,
      BindingKey key) {
    if (! (key.getTypeName() instanceof ClassName)) {
      return false;
    }
    return isEitherComponentBuilder(
        env.getElementUtils().getTypeElement(getClassCanonicalName((ClassName) key.getTypeName())));
  }

  public boolean isEitherComponentBuilder(Element element) {
    return isComponentBuilder(element) || isSubcomponentBuilder(element);
  }

  /**
   * Returns true if the given type can be bound to some type.
   */
  public boolean isBindableType(TypeMirror type) {
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
    // logger.n("isBindableType: %s : %s : %s", type, type.getKind(), result);
    return result;
  }

  public BindingKey getKeyProvidedByMethod(ExecutableElement method) {
    return BindingKey.get(method.getReturnType(), getQualifier(method));
  }

  public Set<String> getPackages(Set<TypeElement> typeElements, Set<Element> elements) {
    Set<String> result = new HashSet<>();
    for (TypeElement m : typeElements) {
      String packageString = getPackage(m).getQualifiedName().toString();
      logPackageSource(m, packageString);
      result.add(packageString);
    }
    for (Element e : elements) {
      String packageString = getPackage(e).getQualifiedName().toString();
      logPackageSource(e, packageString);
      result.add(packageString);
    }
    return result;
  }

  private void logPackageSource(Element e, String packageString) {
    if ("com.google.android.apps.gmm.experiences.details.modules.feedback.viewmodelimpl"
        .equals(packageString)) {
      // logger.e("package: %s by %s", packageString, e);
    }
  }

  /**
   * Return {@link DependencyInfo} for the generalized {@link BindingKey} for
   * the give key. Null if not applicable or not exist.
   */
  public DependencyInfo getDependencyInfoByGeneric(
      SetMultimap<BindingKey, DependencyInfo> dependencies, BindingKey key) {
    TypeName typeName = key.getTypeName();
    // Preconditions.checkArgument(
    //     key.getQualifier() == null,
    //     String.format(
    //         "Binding to %s is supposed to be resolved through generic type of %s"
    //             + "but has non-null qualifier.",
    //         key,
    //         typeName));
    if (typeName instanceof ParameterizedTypeName) {
      ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
      ClassName rawTypeName = parameterizedTypeName.rawType;
      BindingKey rawKey = BindingKey.get(rawTypeName);
      if (dependencies.containsKey(rawKey)) {
        DependencyInfo dependencyInfo = Iterables.getOnlyElement(dependencies.get(rawKey));
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
        Set<BindingKey> specializedDependencies =
            specializeIfNeeded(dependencyInfo.getDependencies(), mapTypeVariableToSpecialized);
        return new DependencyInfo(
            dependencyInfo.getDependencySourceType(),
            key,
            specializedDependencies,
            dependencyInfo.getSourceClassElement(),
            dependencyInfo.getProvisionMethodElement(),
            dependencyInfo.getType());
      }
    }
    return null;
  }

  public Map<TypeVariableName, TypeName> getMapFromTypeVariableToSpecialized(
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

  public Set<BindingKey> specializeIfNeeded(
      Set<BindingKey> keys, Map<TypeVariableName, TypeName> map) {
    Set<BindingKey> result = new HashSet<>();
    for (BindingKey k : keys) {
      result.add(specializeIfNeeded(k, map));
    }

    return result;
  }

  /**
   * Returns a {@link TypeName} with TypeVariable replaced by specialType if
   * applicable.
   */
  public BindingKey specializeIfNeeded(
      BindingKey dependencyKey, Map<TypeVariableName, TypeName> map) {
    AnnotationSpec qualifier = dependencyKey.getQualifier();
    TypeName typeName = specializeIfNeeded(dependencyKey.getTypeName(), map);
    return BindingKey.get(typeName, qualifier);
  }

  /**
   * Returns true if the given type can be bound to some type. Note: this should
   * not be used with raw type of generic type.
   */
  public boolean isBindable(TypeName typeName) {
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

  public PackageElement getPackage(Element element) {
    Preconditions.checkArgument(
        Sets.newHashSet(
                ElementKind.CLASS,
                ElementKind.METHOD,
                ElementKind.CONSTRUCTOR,
                ElementKind.FIELD,
                ElementKind.INTERFACE)
            .contains(element.getKind()),
        "Unexpected element: " + element + " with kind: " + element.getKind());
    Element result = element.getEnclosingElement();
    ElementKind elementKind = result.getKind();
    while (!elementKind.equals(ElementKind.PACKAGE)) {
      Preconditions.checkState(
          elementKind.isClass() || elementKind.isInterface(), String
              .format("utils.getPackage: unexpected kind: %s for type: %s", elementKind,
                  element));
      result = result.getEnclosingElement();
      elementKind = result.getKind();
    }
    return (PackageElement) result;
  }

  public String getPackageString(TypeElement typeElement) {

    return getPackage(typeElement).getQualifiedName().toString();
  }

  /** Only for methods whose dependencies are either not generic or fully specialized, otherwise use
   * {@link #getDependenciesFromMethod(ExecutableType, ExecutableElement)}
  */
  public List<BindingKey> getDependenciesFromExecutableElement(
      ExecutableElement executableElement) {

    List<BindingKey> keys = new ArrayList<>();
    for (VariableElement variableElement : executableElement.getParameters()) {
      Preconditions.checkArgument(
          !isNotSpecializedGeneric(TypeName.get(variableElement.asType())),
          "unspecialized method: " + executableElement);
      keys.add(BindingKey.get(variableElement.asType(), getQualifier(variableElement)));
    }
    return keys;
  }

  public List<BindingKey> getDependenciesFromMethod(
      ExecutableType executableType, ExecutableElement executableElement) {
    List<BindingKey> keys = new ArrayList<>();

    for (int i = 0; i < executableType.getParameterTypes().size(); i ++) {
      keys.add(
          BindingKey.get(
              executableType.getParameterTypes().get(i),
              getQualifier(executableElement.getParameters().get(i))));
    }
    return keys;
  }

  public String getQualifiedName(TypeElement typeElement) {
    return typeElement.getQualifiedName().toString();
  }

  public String getGetMethodName(TypeElement cls) {
    return getGetMethodName(ClassName.get(cls));
  }

  public String getCanonicalName(ClassName className) {
    Joiner joiner = Joiner.on(".");
    return joiner.join(
        Lists.asList(className.packageName(), className.simpleNames().toArray(new String[0])));
  }

  public List<VariableElement> getInjectedFields(
      TypeElement cls, ProcessingEnvironment env) {
    List<VariableElement> result = new ArrayList<>();
    for (Element element : cls.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.FIELD) && isInjected(element)) {
        result.add((VariableElement) element);
      }
    }
    return result;
  }

  public List<ExecutableElement> getInjectedMethods(
      TypeElement cls, ProcessingEnvironment env) {
    List<ExecutableElement> result = new ArrayList<>();
    for (Element element : cls.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.METHOD) && isInjected(element)) {
        result.add((ExecutableElement) element);
      }
    }
    return result;
  }

  public boolean hasInjectedFieldsOrMethods(TypeElement cls, ProcessingEnvironment env) {
    return !getInjectedFields(cls, env).isEmpty() || !getInjectedMethods(cls, env).isEmpty();
  }

  public boolean hasInjectedFieldsOrMethodsRecursively(TypeElement cls, ProcessingEnvironment env) {
    if (!getInjectedFields(cls, env).isEmpty() || !getInjectedMethods(cls, env).isEmpty()) {
      return true;
    }
    if (cls.getSuperclass().getKind().equals(TypeKind.NONE)) {
      return false;
    }
    TypeElement parent = (TypeElement) ((DeclaredType) cls.getSuperclass()).asElement();
    return hasInjectedFieldsOrMethodsRecursively(parent, env);
  }

  public boolean isInjectionMethod(Element element) {
    if (!isMethod(element)) {
      return false;
    }
    if(!element.getModifiers().contains(Modifier.ABSTRACT)) {
      return false;
    }
    ExecutableElement executableElement = (ExecutableElement) element;
    return executableElement.getReturnType().getKind().equals(TypeKind.VOID)
        && (executableElement.getParameters().size() == 1);
  }

  public boolean isProvisionMethodInInjector(Element element) {
    if (!isMethod(element)) {
      return false;
    }
    if(!element.getModifiers().contains(Modifier.ABSTRACT)) {
      return false;
    }
    ExecutableElement executableElement = (ExecutableElement) element;
    return isBindableType(executableElement.getReturnType());
  }

  public boolean isEitherComponentProvisionMethod(Element element) {
    return isComponentProvisionMethod(element) || isSubcomponentProvisionMethod(element);
  }

  public boolean isComponentProvisionMethod(Element element) {
    if (!isMethod(element)) {
      return false;
    }

    //System.out.println("xxx, element before getReturnTypeElement " + element);
    TypeElement returnType = getReturnTypeElement((ExecutableElement) element);
    if (returnType == null) {
      return false;
    }

    //System.out.println("xxx, element before isSubComponent " + element);

    return isComponent(returnType);
  }

  public boolean isSubcomponentProvisionMethod(Element element) {
    if (!isMethod(element)) {
      return false;
    }

    //System.out.println("xxx, element before getReturnTypeElement " + element);
    TypeElement returnType = getReturnTypeElement((ExecutableElement) element);
    if (returnType == null) {
      return false;
    }

    //System.out.println("xxx, element before isSubComponent " + element);

    return isSubcomponent(returnType);
  }

  public boolean isEitherComponentBuilderProvisionMethod(Element element) {
    return isComponentBuilderProvisionMethod(element)
        || isSubcomponentBuilderProvisionMethod(element);
  }

  public boolean isComponentBuilderProvisionMethod(Element element) {
    if (!isMethod(element)) {
      return false;
    }

    //System.out.println("xxx, element before getReturnTypeElement " + element);
    TypeElement returnType = getReturnTypeElement((ExecutableElement) element);
    if (returnType == null) {
      return false;
    }

    //System.out.println("xxx, element before isSubComponent " + element);

    return isComponentBuilder(returnType);
  }

  public boolean isSubcomponentBuilderProvisionMethod(Element element) {
    if (!isMethod(element)) {
      return false;
    }

    //System.out.println("xxx, element before getReturnTypeElement " + element);
    TypeElement returnType = getReturnTypeElement((ExecutableElement) element);
    if (returnType == null) {
      return false;
    }

    //System.out.println("xxx, element before isSubComponent " + element);

    return isSubcomponentBuilder(returnType);
  }

  /**
   * Returns return type element if it is TypeKind.Declared, null otherwise.
   * @param executableElement
   * @return
   */
  public static TypeElement getReturnTypeElement(ExecutableElement executableElement) {
    TypeMirror typeMirror = executableElement.getReturnType();
    if (!typeMirror.getKind().equals(TypeKind.DECLARED)) {
      return null;
    }
    return (TypeElement) ((DeclaredType) typeMirror).asElement();
  }

  public static boolean isMethod(Element element) {
    return element.getKind().equals(ElementKind.METHOD);
  }

  public boolean hasAnonymousParentClass(TypeElement cls) {
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
  public ExecutableElement findInjectedCtor(TypeElement cls) {
    for (Element element : cls.getEnclosedElements()) {
      // messager.printMessage(Kind.NOTE, TAG + ".findInjectedCtor element: " + element);
      if (element.getKind().equals(ElementKind.CONSTRUCTOR) && isInjected(element)) {
        return (ExecutableElement) element;
      }
    }
    return null;
  }

  public ExecutableElement findCtor(TypeElement cls) {
    for (Element element : cls.getEnclosedElements()) {
      // messager.printMessage(Kind.NOTE, TAG + ".findInjectedCtor element: " + element);
      if (element.getKind().equals(ElementKind.CONSTRUCTOR)) {
        return (ExecutableElement) element;
      }
    }
    return null;
  }

  /**
   * Returns whether the given {@link AccessibleObject} is injected.
   */
  public boolean isInjected(Element element) {
    return hasAnnotationMirror(element, Inject.class);
  }

  public static TypeName getTypeName(TypeMirror typeMirror) throws ResolveTypeMirrorException {
    try {
      return TypeName.get(typeMirror);
    } catch (Exception e) {
      throw new RuntimeException("getTypeName wrong for: " + typeMirror, e);
    }
  }

  public boolean isDaggerMembersInjector(BindingKey key) {
    return isDaggerMembersInjector(key.getTypeName());
  }

  public boolean isDaggerMembersInjector(TypeName type) {
    return isAnyTypeOf(type, MembersInjector.class);
  }

  public boolean isProviderOrLazy(BindingKey key) {
    return isProviderOrLazy(key.getTypeName());
  }

  public boolean isProviderOrLazy(TypeName type) {
    return isAnyTypeOf(type, Provider.class, Lazy.class);
  }

  private static boolean isAnyTypeOf(TypeName type, Class<?>... targets) {
    if (! (type instanceof ParameterizedTypeName)) {
      return false;
    }
    ParameterizedTypeName typeName = (ParameterizedTypeName) type;

    ClassName rawType = typeName.rawType;
    return Sets.newHashSet(
            Collections2.transform(Sets.newHashSet(targets), input -> ClassName.get(input)))
        .contains(rawType);
  }

  /**
   * Return null means the give key is not bound, which is an error. We cannot
   * return empty Set in this case because that means the binding exists and
   * depends on nothing.
   */
  @Nullable
  public Set<DependencyInfo> getDependencyInfo(
      SetMultimap<BindingKey, DependencyInfo> dependencies, BindingKey key) {
    if (dependencies.containsKey(key)) {
      return dependencies.get(key);
    } else if (key.getTypeName().isPrimitive() || key.getTypeName().isBoxedPrimitive()) {
      if (dependencies.containsKey(key.boxOrUnbox())) {
        return dependencies.get(key.boxOrUnbox());
      } else {
        return null;
      }
    } else if (isProviderOrLazy(key)) {
      return getDependencyInfo(dependencies, getElementKeyForParameterizedBinding(key));
    } else if (isMap(key)) {
      // Handle the case that value is dagger built-in type.
      BindingKey peeledKey = peelMapWithBuiltinValue(key);
      if (peeledKey != null) {
        return getDependencyInfo(dependencies, peeledKey);
      } else {
        return null;
      }
    } else {
      /**
       * If Optional<Lazy|Provider> finally boil down to a @BindsOptionalOf, we found it. Other
       * cases, either dependencies not found or found something other than @BindsOptionalOf, it is
       * invalid and deemed not found. Once we can confirm that the dependency exists, we can find
       * whether it is present or absent by finding the dependency of its element , which is
       * straightforward. {@link DependencyCollector} will stop at {@link DependencyInfo} for
       * the @BindsOptionalOf. Scope calculating and code generating will drill down through the
       * element of the Optional.
       */
      if (isOptional(key)) {
        BindingKey elementKey = getElementKeyForParameterizedBinding(key);
        if (isProviderOrLazy(elementKey)) {
          BindingKey elementElementKey = getElementKeyForParameterizedBinding(elementKey);
          BindingKey newOptionalKey =
              BindingKey.get(
                  ParameterizedTypeName.get(
                      ClassName.get(Optional.class), elementElementKey.getTypeName()),
                  key.getQualifier());
          Set<DependencyInfo> dependencyInfos = getDependencyInfo(dependencies, newOptionalKey);
          if (isBindsOptionalOf(dependencyInfos)) {
            return dependencyInfos;
          }
        }
      }
      DependencyInfo dependencyInfo = getDependencyInfoByGeneric(dependencies, key);
      if (dependencyInfo == null) {
        return null;
      } else {
        return Sets.newHashSet(dependencyInfo);
      }
    }
  }

  /**
   * Return if the @BindsOptionalOf with the given dependencies present.
   */
  public boolean isBindsOptionalOfPresent(
      SetMultimap<BindingKey, DependencyInfo> dependencyMap, Set<DependencyInfo> dependencyInfos) {
    Preconditions.checkArgument(isBindsOptionalOf(dependencyInfos));
    BindingKey elementKey =
        getElementKeyForParameterizedBinding(
            Iterables.getOnlyElement(dependencyInfos).getDependant());
    return getDependencyInfo(dependencyMap, elementKey) != null;
  }

  private static boolean isOptionalKey(BindingKey key) {
    TypeName typeName = key.getTypeName();
    return typeName instanceof ParameterizedTypeName
        && ((ParameterizedTypeName) typeName).rawType.equals(ClassName.get(Optional.class));
  }

  /**
   * If the key comes with value of type that has dagger builtin binding, return one
   * with the type replaced by the element of the original value type, null otherwise.
   * Nested built-in binding like Lazy<Lazy<Foo>>, Provider<Lazy<Foo>>, etc, are not
   * supported.
   */
  @Nullable
  public BindingKey peelMapWithBuiltinValue(BindingKey key) {
    Preconditions.checkState(isMap(key), String.format("Expect a map but got %s", key));
    ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) key.getTypeName();
    TypeName valueType = parameterizedTypeName.typeArguments.get(1);
    if (isProviderOrLazy(valueType)) {
      TypeName mapKeyType = parameterizedTypeName.typeArguments.get(0);
      TypeName elementType =
          Iterables.getOnlyElement(((ParameterizedTypeName) valueType).typeArguments);
      TypeName newType =
          ParameterizedTypeName.get(ClassName.get(Map.class), mapKeyType, elementType);
      return BindingKey.get(newType, key.getQualifier());
    }

    return null;
  }

  public boolean isMapWithBuiltinValueType(BindingKey key) {
    return isMap(key) && peelMapWithBuiltinValue(key) != null;
  }

  /**
   * Returns if key is a map with type variables.
   */
  public boolean isMap(BindingKey key) {
    TypeName typeName = key.getTypeName();
    if (!(typeName instanceof ParameterizedTypeName)) {
      return false;
    }
    ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
    return parameterizedTypeName.rawType.equals(ClassName.get(Map.class));
  }

  /**
   * Returns if key is a set with type variables.
   */
  public boolean isSet(BindingKey key) {
    TypeName typeName = key.getTypeName();
    if (!(typeName instanceof ParameterizedTypeName)) {
      return false;
    }
    ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
    return parameterizedTypeName.rawType.equals(ClassName.get(Set.class));
  }

  public boolean isMultiBinding(BindingKey key) {
    return isMap(key) || isSet(key);
  }

  /**
   * Return {@link BindingKey} for element of the give {@link BindingKey} that
   * is parameterized type with one and only one type parameter.
   */
  @Nullable
  public BindingKey getElementKeyForParameterizedBinding(BindingKey key) {
    ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) key.getTypeName();

    TypeName typeName = Iterables.getOnlyElement(parameterizedTypeName.typeArguments);
    AnnotationSpec qualifier = key.getQualifier();
    return BindingKey.get(typeName, qualifier);
  }

  /**
   * Changes give tree to {@link SetMultimap} from parent to children.
   */
  public <T> SetMultimap<T, T> reverseTree(Map<T, T> childToParentMap) {
    SetMultimap<T, T> parentToChildrenMap = HashMultimap.create();
    for (Map.Entry<T, T> entry : childToParentMap.entrySet()) {
      parentToChildrenMap.put(entry.getValue(), entry.getKey());
    }
    return parentToChildrenMap;
  }

  /**
   * Returns width-first ordered component from the give map.
   */
  public <T> List<T> getOrderedScopes(Map<T, T> childToParentMap) {
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

  public Set<TypeElement> getNonNullaryCtorOnes(Set<TypeElement> elements) {
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
  public boolean hasProvisionMethod(DeclaredType moduleType) {
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
  public TypeElement getModuleScope(DeclaredType moduleType,
      ScopeAliasCondenser scopeAliasCondenser) {
    TypeElement moduleElement = (TypeElement) moduleType.asElement();
    Preconditions.checkArgument(
        moduleElement.getAnnotation(Module.class) != null,
        String.format("not module: %s.", moduleType));
    for (Element element : moduleElement.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.METHOD)
          && (element.getAnnotation(Provides.class) != null)) {
        for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
          TypeElement annotationElement =
              (TypeElement) annotationMirror.getAnnotationType().asElement();
          Annotation scope =
              annotationElement.getAnnotation(Scope.class);
          // TODO: handle @Resuable in a more robust way.
          if (scope != null && !annotationElement.getSimpleName().contentEquals("Reusable")) {
            return scopeAliasCondenser.getCoreScopeForAlias(annotationElement);
          }
        }
      }
    }
    return null;
  }

  /**
   * Returns modules included and created by {@link ContributesAndroidInjector}.
   */

  @SuppressWarnings("unchecked")
  public Set<TypeElement> findAllModulesRecursively(Elements elements, TypeElement module) {
    Set<TypeElement> result = new HashSet<>();

    result.add(module);
    // messager.printMessage(Kind.NOTE, TAG + ".findAllModulesRecursively, added " + module);

    // included
    for (AnnotationMirror annotationMirror : module.getAnnotationMirrors()) {
      // TODO: only handle @Module
      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
          annotationMirror.getElementValues().entrySet()) {
        ExecutableElement key = entry.getKey();
        // messager.printMessage(Kind.NOTE, TAG + ".findAllModulesRecursively, key " + key);

        /** Checks for {@link Module.includes}. */
        if (key.getSimpleName().contentEquals("includes")) {

          for (AnnotationValue annotationValue :
              (List<AnnotationValue>) entry.getValue().getValue()) {
            TypeElement childModule =
                (TypeElement) ((DeclaredType) annotationValue.getValue()).asElement();
            result.addAll(findAllModulesRecursively(elements, childModule));
          }
          // messager.printMessage(Kind.NOTE, TAG + ".findAllModulesRecursively, result " + result);

        }
      }
    }

    /**
     * by {@link ContributesAndroidInjector}
     */

    Set<TypeElement> modulesGenerated = collectModulesByContributesAndroidInjectorInModule(elements, messager,
        module);
    if (modulesGenerated != null) {
      // trimModules(modulesGenerated);
      result.addAll(modulesGenerated);
    }

    // logger.n("module: %s\nresult: %s", module, result);

    return result;
  }

  private static void trimModules(Set<TypeElement> modulesGenerated) {
    Set<TypeElement> trimmed = new HashSet<>();
    for (TypeElement i : modulesGenerated) {
      String name = i.getSimpleName().toString();
      boolean should = true;
      for (String s : nameShouldBeKept) {
        if (name.contains(s)) {
          should = false;
        }
      }
      if (should) {
        trimmed.add(i);
      }
    }
    for (TypeElement i : trimmed) {
      modulesGenerated.remove(i);
    }
  }

  /**
   * Returns modules included and created by {@link ContributesAndroidInjector}.
   */
  public Set<TypeElement> findAllModulesRecursively(Collection<TypeElement> inModules,
      Elements elements) {
    Set<TypeElement> result = new HashSet<>();

    for (TypeElement module : inModules) {
      result.addAll(findAllModulesRecursively(elements, module));

      // messager.printMessage(
      //     Kind.NOTE,
      //     TAG
      //         + ".findAllModulesRecursively loop, module: "
      //         + module
      //         + " result: "
      //         + result.size()
      //         + " "
      //         + result);
    }

    return result;
  }

  public List<TypeName> sortTypeName(Collection<TypeName> items) {
    Ordering<TypeName> ordering = new Ordering<TypeName>() {
      @Override
      public int compare(TypeName left, TypeName right) {
        return getQualifiedName(left).compareTo(getQualifiedName(right));
      }
    };
    return ordering.immutableSortedCopy(items);
  }

  private static String getQualifiedName(TypeName typeName) {
    ClassName className;
    if (typeName instanceof  ParameterizedTypeName) {
      className = ((ParameterizedTypeName) typeName).rawType;
    } else {
      Preconditions.checkArgument(
          typeName instanceof ClassName, "Unexpected type found: " + typeName);
      className = (ClassName) typeName;
    }
    return Joiner.on(".").join(className.simpleNames());
  }

  public List<TypeElement> sortByFullName(Collection<TypeElement> typeElements) {
    Ordering<TypeElement> ordering = new Ordering<TypeElement>() {
      @Override
      public int compare(TypeElement left, TypeElement right) {
        return left.getQualifiedName().toString().compareTo(right.getQualifiedName().toString());
      }
    };
    return ordering.immutableSortedCopy(typeElements);
  }

  /**
   * Sorts by first key.typeName then key.qualifier.
   */
  public List<BindingKey> sortBindingKeys(Collection<BindingKey> keys) {
    Ordering<BindingKey> ordering = new Ordering<BindingKey>() {
      @Override
      public int compare(BindingKey left, BindingKey right) {
        int result = left.getTypeName().toString().compareTo(right.getTypeName().toString());
        if (result != 0) {
          return result;
        }
        return left.getQualifier().toString().compareTo(right.getQualifier().toString());
      }
    };
    return ordering.immutableSortedCopy(keys);
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
      return getClassCanonicalName((ClassName) typeName)
          .replace(".", REPLACEMENT_FOR_INVALID_CHARS);
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

  public String getSourceCodeName(TypeElement typeElement) {
    return getSourceCodeName(TypeName.get(typeElement.asType()));
  }

  /** Adds set method for type to builder. */
  public void addSetMethod(
      Types types, Elements elements, ClassName builderParentClassName,
      Builder builder,
      TypeName type) {
    addSetMethod(types, elements, builderParentClassName, builder, type, null);
  }

  public void addSetMethod(
      Types types,
      Elements elements,
      ClassName builderParentClassName,
      Builder builder,
      TypeName type,
      @Nullable String methodName) {
    addSetMethod(types, elements, builderParentClassName, builder, type, methodName, null);
  }

  public void addSetMethod(
      Types types,
      Elements elements,
      @Nullable ClassName builderParentClassName,
      Builder builder,
      TypeName type,
      String methodName,
      @Nullable String builderName) {
    addSetMethod(
        types,
        elements,
        builderParentClassName,
        builder,
        BindingKey.get(type),
        methodName,
        builderName);
  }

  public void addSetMethod(
      Types types,
      Elements elements,
      @Nullable ClassName builderParentClassName,
      Builder builder,
      BindingKey key,
      @Nullable String methodName,
      @Nullable String builderName) {
    TypeName type = key.getTypeName();
    String fullName = getSourceCodeName(key);
    // TODO: the cast is not safe.
    if (methodName == null) {
      methodName = "set_" + fullName;
      // TODO: revisit, is this dagger contract?
      if (key.getTypeName() instanceof  ClassName) {
        methodName = createDaggerCompatibleName(key);
      }
    }
    builder.addField(type, fullName, Modifier.PRIVATE);
    String argName = "arg";
    if (builderName == null) {
      builderName = "Builder";
    }
    MethodSpec.Builder methodBuilder =
        MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(type, argName)
            .addCode("this.$N = $N;", fullName, argName);
    if (builderParentClassName != null) {
      methodBuilder
          .addCode("return this;")
          .returns(
              ClassName.get(
                  builderParentClassName.packageName(),
                  builderParentClassName.simpleName(),
                  builderName));
    }

    builder.addMethod(methodBuilder.build());
  }

  private String createDaggerCompatibleName(BindingKey key) {
    String simpleName = ((ClassName) key.getTypeName()).simpleName();
    return simpleName.substring(0, 1).toLowerCase() + simpleName.substring(1);
  }

  public <K, V> SetMultimap<V, K> reverseSetMultimap(SetMultimap<K, V> map) {
    SetMultimap<V, K> result = HashMultimap.create();
    for (Map.Entry<K, V> entry : map.entries()) {
      result.put(entry.getValue(), entry.getKey());
    }
    return result;
  }
  public <K, V> Map<V, K> reverseSetMultimapToMap(SetMultimap<K, V> map) {
    Map<V, K> result = new HashMap<>();
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
  public boolean isModule(Class<?> clazz) {
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
  public Annotation getQualifierAnnotation(AccessibleObject accessibleObject) {
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
  public DeclaredType getQualifierAnnotation(Element element) {
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
  public Constructor<?> findInjectedCtor(Class<?> clazz) {
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
  private List<Class<?>> getIncludedModules(Class<?> module) {
    List<Class<?>> result = new ArrayList<>();
    Module childModule = module.getAnnotation(Module.class);
    Class<?>[] includes = childModule.includes();
    result.addAll(Lists.newArrayList(includes));
    for (Class<?> clazz : includes) {
      logger.n("module: " + module + " child: " + clazz);
      result.addAll(getIncludedModules(clazz));
    }
    return result;
  }

  /**
   * Returns {@link Module}s referenced by the given {@link Module}s
   * recursively.
   */
  public Set<Class<?>> findAllModules(Set<Class<?>> modules) {
    Set<Class<?>> result = new HashSet<>();

    for (Class<?> module : modules) {
      result.addAll(getIncludedModules(module));
    }

    result.addAll(modules);
    return result;
  }

  public boolean hasInjectedFieldsOrMethods(Class<?> clazz) {
    return !getInjectedFields(clazz).isEmpty() || !getInjectedMethods(clazz).isEmpty();
  }

  public List<Field> getInjectedFields(Class<?> clazz) {
    return getInjected(Arrays.asList(clazz.getDeclaredFields()));
  }

  public List<Method> getInjectedMethods(Class<?> clazz) {
    return getInjected(Arrays.asList(clazz.getDeclaredMethods()));
  }

  public List<Method> getProvisionMethods(Class<?> clazz) {
    return filterProvides(Arrays.asList(clazz.getDeclaredMethods()));
  }

  private static <T extends AccessibleObject> List<T> getInjected(Iterable<T> all) {
    List<T> result = new ArrayList<>();
    for (T t : all) {
      if (isInjected(t)) {
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
  public String lowerFirst(String s) {
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

  public ProvisionType getProvisionType(ExecutableElement method) {
    if (isAnnotationPresent(method, IntoMap.class)) {
      return MAP;
    } else if (isAnnotationPresent(method, IntoSet.class)) {
      return SET;
    } else if (isAnnotationPresent(method, ElementsIntoSet.class)) {
      return SET_VALUES;
    } else if (isAnnotationPresent(method, Multibinds.class)) {
      TypeName returnType = ((ParameterizedTypeName) ParameterizedTypeName.get(method.getReturnType())).rawType;
      if (returnType.equals(TypeName.get(Set.class))) {
        return SET_VALUES;
      } else {
        Preconditions.checkState(
            returnType.equals(TypeName.get(Map.class)),
            "Multibinding for unexpected type: " + method);
        return MAP;
      }
    } else if (isAnnotationPresent(method, Binds.class)) {
      return UNIQUE;
    } else if (isAnnotationPresent(method, BindsOptionalOf.class)) {
      return UNIQUE;
    }

    return ProvisionType.fromDaggerType(method.getAnnotation(Provides.class).type());
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

  public boolean hasAnonymousParentClass(Class<?> cls) {
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
      logger.n("class: %s, exception: %s.", cls, e);
      return false;
    }
  }

  /**
   * Returns a {@link TypeName} with all the {@link TypeVariableName} in the
   * given typeName replaced with the {@link TypeName} found in map the the
   * {@link TypeVariableName} if necessary.
   */
  public TypeName specializeIfNeeded(
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

  public String getPackagedInjectorSimpleName(Class<? extends Annotation> scope) {
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

  public String getMultiBindingInjectorSimpleName(Class<? extends Annotation> scope) {
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

  public String getGetMethodName(Class<?> cls) {
    return getGetMethodName(ClassName.get(cls));
  }

  public String getGetMethodName(ClassName className) {
    return String.format(Locale.US, "get_%s", getClassCanonicalName(className).replace(".", "_"));
  }

  /** Return the first build method, null if not found. */
  public @Nullable ExecutableElement getBuildMethod(
      Types types, Elements elementUtils, TypeElement builder) {
    ExecutableElement result = null;
    for (Element element : elementUtils.getAllMembers(builder)) {
      // logger.n("element: %s", element);
      if (isBuildMethod(types, element, builder)) {
        result = (ExecutableElement) element;
        return result;
      }
    }
    return result;
  }

  /** Returns whether the element is build method in a dagger (sub)component builder */
  private static boolean isBuildMethod(Types types, Element element,
      TypeElement builder) {
    if (!isMethod(element)) {
      return false;
    }
    ExecutableElement method = (ExecutableElement) element;
    if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
      return false;
    }
    if (!(method.getParameters().size() == 0)) {
      return false;
    }
    TypeElement returnType = getReturnTypeElement(method);

    if (returnType == null) {
      return false;
    }

    return true;
    // if (returnType instanceof ParameterizedType) {
    //   returnType = ((ParameterizedType) returnType).getRawType();
    // }

    // boolean result = types.isSubtype(builder.getEnclosingElement().asType(), returnType.asType());
    // messager.printMessage(
    //     Kind.NOTE,
    //     TAG
    //         + " isBuildMethod: "
    //         + element
    //         + " "
    //         + result
    //         + " component: "
    //         + builder.getEnclosingElement().asType()
    //         + " return type: "
    //         + returnType.asType());
    // return result;
  }

  /** Return the setter name for the given type from the given builder, null if setter not found */
  public String getBuilderSetterName(
      Types types, Elements elementUtils, TypeElement builder, TypeElement type) {
    return getBuilderSetterName(types, elementUtils, builder, BindingKey.get(type));
  }

  public String getBuilderSetterName(
      Types types, Elements elementUtils, TypeElement builder, BindingKey key) {
    return getBuilderSetter(types, elementUtils, builder, key).getSimpleName().toString();
  }

  /** Returns the setter in the builder that sets the given key, null if not found. */
  public @Nullable ExecutableElement getBuilderSetter(
      Types types, Elements elementUtils, TypeElement builder, BindingKey key) {
    logger.n( "builder: %s key: %s", builder, key);

    for (Element element : elementUtils.getAllMembers(builder)) {
      if (!isBuilderSetter(types, builder, element)) {
        continue;
      }

      logger.n( "builder: %s setter: %s", builder, element);
      ExecutableElement method = (ExecutableElement) element;
      ExecutableType executableType =
          (ExecutableType) types.asMemberOf((DeclaredType) builder.asType(), method);

      TypeMirror argumentType = Iterables.getOnlyElement(executableType.getParameterTypes());
      AnnotationMirror annotationTypeMirror =
          getQualifier(Iterables.getOnlyElement(method.getParameters()));
      if (!BindingKey.get(argumentType, annotationTypeMirror).equals(key)) {
        continue;
      }

      return method;
    }
    return null;
  }

  public boolean isBuilderSetter(Types types, TypeElement builder,
      Element element) {
    if (!isMethod(element)) {
      return false;
    }
    ExecutableElement method = (ExecutableElement) element;
    if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
      return false;

    }
    List<? extends VariableElement> parameters = method.getParameters();
    if (parameters.size() != 1) {
      return false;
    }
    ExecutableType executableType =
        (ExecutableType) types.asMemberOf((DeclaredType) builder.asType(), method);
    //messager.printMessage(Kind.NOTE, TAG + " getBuilderSetterName. After " + executableType);
    boolean returnVoid = method.getReturnType().getKind() == TypeKind.VOID;
    if (!types.isSubtype(builder.asType(), executableType.getReturnType()) && !returnVoid) {
      return false;
    }
    return true;
  }

  /**
   * Returns the name for generated class for the given (sub)component.
   */
  public String getComponentImplementationSimpleNameFromInterface(TypeElement component) {
    String packageString = getPackageString(component);
    String classNameString =
        component.getQualifiedName().toString().substring(packageString.length());
    if (classNameString.startsWith(".")) {
      classNameString = classNameString.substring(1);
    }
    return "Dagger" + classNameString.replace(".", "_");
  }

  public TypeElement getTypeElementForClassName(ClassName className) {
    return elements.getTypeElement(getClassCanonicalName(className));
  }

  public TypeElement getTypeElement(BindingKey key) {
    return getTypeElement(key.getTypeName());
  }

  public TypeElement getTypeElement(TypeName typeName) {
    // logger.e("type: %s", typeName);
    Preconditions.checkArgument(typeName instanceof ClassName, "expected type: " + typeName);
    return Preconditions.checkNotNull(
        elements.getTypeElement(getClassCanonicalName((ClassName) typeName)),
        "class not found for: %s",
        typeName);
  }

  // Returns whether the given method is a @Binds one.
  public boolean isBindsMethod(Element method) {
    return isMethod(method)
        && method.getModifiers().contains(Modifier.ABSTRACT)
        && hasAnnotationMirror(method, Binds.class)
        && ((ExecutableElement) method).getParameters().size() == 1;
  }

  // Returns whether the given method is a @Binds one.
  public boolean isMultibindsMethod(Element method) {
    return isMethod(method)
        && method.getModifiers().contains(Modifier.ABSTRACT)
        && hasAnnotationMirror(method, Multibinds.class)
        && ((ExecutableElement) method).getParameters().isEmpty();
  }

  public boolean isComponentDependencyProvisionMethod(Element e) {
    if (!isMethod(e)) {
      return false;
    }
    ExecutableElement method = (ExecutableElement) e;
    Set<Modifier> modifiers = method.getModifiers();
    return !modifiers.contains(Modifier.PRIVATE)
        && !modifiers.contains(Modifier.PROTECTED)
        && method.getParameters().isEmpty()
        && isBindableType(method.getReturnType());
  }

  public boolean isBindsInstanceMethod(Element e) {
    return isMethod(e) && hasAnnotationMirror(e, BindsInstance.class);
  }

  public BindingKey getKeyForOnlyParameterOfMethod(Types types, DeclaredType containingType,
      Element e) {
    ExecutableType method = (ExecutableType) types.asMemberOf(containingType, e);
    VariableElement variableElement =
        Iterables.getOnlyElement(((ExecutableElement) e).getParameters());
    TypeMirror argumentType = Iterables.getOnlyElement(method.getParameterTypes());
    AnnotationMirror annotationTypeMirror = getQualifier(variableElement);

    return BindingKey.get(argumentType, annotationTypeMirror);
  }

  public BindingKey getBindingKeyForMethodParameter(VariableElement variableElement) {
    DeclaredType declaredType = (DeclaredType)variableElement.asType();
    AnnotationMirror qualifier = getQualifier(variableElement);
    return BindingKey.get(declaredType, qualifier);
  }

  /**
   * Returns {@link #getSourceCodeName}(qualifier) + "__" + {@link #getSourceCodeName} (type), or
   * {@link #getSourceCodeName}(type) if no qualifier.
   */
  static String getSourceCodeNameHandlingBox(BindingKey key,
      SetMultimap<BindingKey, DependencyInfo> dependencies) {
    // messager.printMessage(Kind.NOTE, "getMethodName for key: " + key);
    String result = getSourcCodeNameForQualifier(key);
    Set<DependencyInfo> dIs = getDependencyInfosHandlingBox(dependencies,
        key);
    if (dIs != null) {
      key =
          Preconditions.checkNotNull(Iterables.getFirst(dIs, null), "key: " + key + " dIs: " + dIs)
              .getDependant();
    }

    return result + getSourceCodeName(key.getTypeName());
  }

  static String getSourceCodeName(BindingKey key) {
    // messager.printMessage(Kind.NOTE, "getMethodName for key: " + key);
    String result = getSourcCodeNameForQualifier(key);
    return result + getSourceCodeName(key.getTypeName());
  }

  private static String getSourcCodeNameForQualifier(BindingKey key) {
    StringBuilder builder = new StringBuilder();
    AnnotationSpec qualifier = key.getQualifier();
    if (qualifier != null) {
      ClassName qualifierType = (ClassName) qualifier.type;
      builder.append(getSourceCodeName(qualifierType));
      /**
       * TODO(freeman): handle all illegal chars.
       */
      //if (getCanonicalName(qualifierType).equals(Named.class.getCanonicalName())) {
      if (qualifier.members.get("value") != null) {
        builder
            .append("_")
            .append(
                qualifier
                    .members
                    .get("value")
                    .toString()
                    .replace("\"", REPLACEMENT_FOR_INVALID_CHARS)
                    .replace(".", REPLACEMENT_FOR_INVALID_CHARS)
                    .replace("[", REPLACEMENT_FOR_INVALID_CHARS)
                    .replace("]", REPLACEMENT_FOR_INVALID_CHARS));
      }
      builder.append("__");
    }
    return builder.toString();
  }

  // Returns provide_Blah_Qualifier__Foo_Type for @BlahQualifier FooType.
  static String getProvisionMethodName(SetMultimap<BindingKey, DependencyInfo> dependencies,
      BindingKey key) {
    return "provide_" + getSourceCodeNameHandlingBox(key, dependencies);
  }

  static String getProvisionMethodName(BindingKey key) {
    return "provide_" + getSourceCodeName(key);
  }

  public boolean isOptional(BindingKey key) {
    return key.getTypeName() instanceof ParameterizedTypeName
        && ((ParameterizedTypeName) key.getTypeName())
            .rawType.equals(ClassName.get(Optional.class));
  }

  public boolean isBindsOptionalOf(Set<DependencyInfo> dependencyInfos) {
    if (dependencyInfos == null) {
      return false;
    }
    if (dependencyInfos.size() != 1) {
      return false;
    }
    DependencyInfo dependencyInfo = Iterables.getOnlyElement(dependencyInfos);
    if (!dependencyInfo.getDependencySourceType().equals(DependencySourceType.MODULE)) {
      return false;
    }
    return isBindsOptionalOfMethod(dependencyInfo.getProvisionMethodElement());
  }

  public static java.lang.String capitalize(String s) {
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /** Returns super element, null if no super. */
  public TypeElement getSuper(TypeElement typeElement) {
    TypeMirror typeMirror = typeElement.getSuperclass();
    if (typeMirror.getKind().equals(TypeKind.NONE)) {
      return null;
    }
    return (TypeElement) ((DeclaredType) typeMirror).asElement();
  }
  // DODO: refine this
  public boolean isContributesAndroidInjectorMethod(Element e) {
    return getAnnotationMirror(e, "dagger.android.ContributesAndroidInjector") != null;
  }

  /**
   * Traverses parent class and interfaces to run the {@link Function} for every element. TODO: use
   * direct containing instead of top one for {@link Types#asMemberOf(DeclaredType, Element)} but
   * this seems work.
   *
   * <p>getAllMembers seems have a bug that mark annotated fields methods(or the other way). TODO:
   * use {@link java.util.function.BiConsumer}.
   */
  public void traverseAndDo(
      Types types,
      DeclaredType type,
      TypeElement element,
      Function<Pair<TypeMirror, Element>, Void> function) {
    // logger.n("container " + element);

    TypeMirror typeMirror = element.getSuperclass();
    if (!typeMirror.getKind().equals(TypeKind.NONE)) {
      DeclaredType superClassType = (DeclaredType) typeMirror;
      traverseAndDo(
          types, superClassType, (TypeElement) superClassType.asElement(), function);
    }

    for (TypeMirror i : element.getInterfaces()) {
      DeclaredType iface = (DeclaredType) i;
      traverseAndDo(
          types, iface, (TypeElement) iface.asElement(), function);
    }

    for (Element e : element.getEnclosedElements()) {
      TypeMirror elementType = types.asMemberOf(type, e);
      function.apply(Pair.of(elementType, e));
    }
  }

  @Nullable
  public TypeElement getClosestInjectedAncestor(TypeElement cls) {
    TypeElement clsClosestInjectAncestor = getSuper(cls);
    while (clsClosestInjectAncestor != null
        && !hasInjectedFieldsOrMethods(clsClosestInjectAncestor, processingEnvironment)) {
      clsClosestInjectAncestor = getSuper(clsClosestInjectAncestor);
    }
    return clsClosestInjectAncestor;
  }

  public TypeMirror getTypeFromKey(BindingKey key) {
    return getTypeFromTypeName(key.getTypeName());
  }

  public boolean isKeyByGenericClass(BindingKey key) {
    Preconditions.checkArgument(!isProviderOrLazy(key) && !isOptional(key));
    return isNotSpecializedGeneric(getClassFromKey(key).asType());
  }

  // This does box for primitive types.
  public TypeMirror getTypeFromTypeName(TypeName typeName) {
    Preconditions.checkArgument(!isNotSpecializedGeneric(typeName), "expect specialized: " + typeName);
    // logger.n("typeName: %s", typeName);
    if (typeName.isPrimitive()) {
      typeName = typeName.box();
    }
    if (typeName instanceof  ClassName) {
      return getTypeFromClassName((ClassName) typeName);
    }
    if (typeName instanceof ParameterizedTypeName) {
      return getTypeFromParameterizedTypeName((ParameterizedTypeName) typeName);
    }
    if (typeName instanceof WildcardTypeName) {
      return getTypeFromWildcardTypeName((WildcardTypeName) typeName);
    }
    // logger.e("get type from wrong TypeName: %s", typeName);
    return null;
  }

  /**
   * TODO: TypeName supports multiple bounds while WildcardType might have UnionType for bounds.
   */
  private WildcardType getTypeFromWildcardTypeName(WildcardTypeName typeName) {
    DeclaredType lowerBoundsType =
        typeName.lowerBounds == null || typeName.lowerBounds.isEmpty()
            ? null
            : (DeclaredType) getTypeFromTypeName(typeName.lowerBounds.get(0));
    DeclaredType upperBoundsType =
        typeName.upperBounds == null || typeName.upperBounds.isEmpty()
            ? null
            : (DeclaredType) getTypeFromTypeName(typeName.upperBounds.get(0));
    return types.getWildcardType(upperBoundsType, lowerBoundsType);
  }

  private DeclaredType getTypeFromParameterizedTypeName(ParameterizedTypeName typeName) {
    DeclaredType rawType = getTypeFromClassName(typeName.rawType);
    TypeMirror[] parameterTypes = new TypeMirror[typeName.typeArguments.size()];
    for (int i = 0; i < typeName.typeArguments.size(); i ++) {
      parameterTypes[i] = getTypeFromTypeName(typeName.typeArguments.get(i));
    }
    /*
    logger.n(
        "%s, raw element: %s, params: %s",
        typeName, rawType.asElement(), Lists.newArrayList(parameterTypes));
    */
    switch (parameterTypes.length) {
      case 1:
        return types.getDeclaredType((TypeElement) rawType.asElement(), parameterTypes[0]);
      case 2:
        return types.getDeclaredType(
            (TypeElement) rawType.asElement(), parameterTypes[0], parameterTypes[1]);
      case 3:
        return types.getDeclaredType(
            (TypeElement) rawType.asElement(),
            parameterTypes[0],
            parameterTypes[1],
            parameterTypes[2]);
      case 4:
        return types.getDeclaredType(
            (TypeElement) rawType.asElement(),
            parameterTypes[0],
            parameterTypes[1],
            parameterTypes[2],
            parameterTypes[3]);
      case 5:
        return types.getDeclaredType(
            (TypeElement) rawType.asElement(),
            parameterTypes[0],
            parameterTypes[1],
            parameterTypes[2],
            parameterTypes[3],
            parameterTypes[4]);
      default:
        logger.e("so many parameters ? " + typeName);
        return  null;
    }
  }

  private DeclaredType getTypeFromClassName(ClassName typeName) {
    return types.getDeclaredType(elements.getTypeElement(typeName.toString()));
  }

  /**
   * BazType barMethod() in FooModule will generate module FooModule_BarMethod.
   */
  public String getModuleNameFromContributesAndroidInjectorMethod(TypeElement m,
      ExecutableElement method) {
    ClassName className = getModuleClassNameFromContributesAndroidInjector(m, method);
    return getCanonicalName(className);
  }

  private static ClassName getModuleClassNameFromContributesAndroidInjector(TypeElement m,
      ExecutableElement method) {
    ClassName className = ClassName.get(m);
    Joiner joiner = Joiner.on("_");
    className =
        ClassName.get(
            className.packageName(),
            joiner.join(className.simpleNames())
                + "_"
                + capitalize(method.getSimpleName().toString()));
    return className;
  }

  /**
   * BazType barMethod() in FooNestedModule in FooModule will generate
   * FooModule_FooNestedModule_BarMethod.BazTypeSubcomponent.
   */
  public String getSubcomponentNameFromContributesAndroidInjectorMethod(
      TypeElement m, ExecutableElement method) {
    ClassName className = getModuleClassNameFromContributesAndroidInjector(m,
        method);
    className =
        className.nestedClass(
            ((DeclaredType) method.getReturnType()).asElement().getSimpleName().toString()
                + "Subcomponent");
    return getCanonicalName(className);
  }

  /**
   * Return all the Subcomponents created by {@link ContributesAndroidInjector},
   * empty if none are found, null if there are but {@link
   * ContributesAndroidInjector} has not been processed.
   */
  @Nullable
  public Set<TypeElement> collectSubomponentsByContributesAndroidInjectorInModule(Elements elements,
      Messager messager, TypeElement m) {
    Set<TypeElement> result = new HashSet<>();
    for (Element e : elements.getAllMembers(m)) {
      if (!isContributesAndroidInjectorMethod(e)) {
        continue;
      }
      // messager.printMessage(
      //     Kind.NOTE, TAG + ".collectSubomponentsByContributesAndroidInjectorInModule " + e);
      ExecutableElement method = (ExecutableElement) e;
      String canonicalName = getSubcomponentNameFromContributesAndroidInjectorMethod(m, method);
      // messager.printMessage(
      //     Kind.NOTE,
      //     TAG + ".collectSubomponentsByContributesAndroidInjectorInModule " + canonicalName);
      TypeElement typeElement = elements.getTypeElement(canonicalName);
      if (typeElement == null) {
        logger.w("not found:  %s", canonicalName);
        return null;
      }
      result.add(typeElement);
    }
    return result;
  }
  /**
   * Return all the modules created by {@link ContributesAndroidInjector}, empty if none are found,
   * null if there are but {@link ContributesAndroidInjector} has not been processed.
   */
  @Nullable
  public Set<TypeElement> collectModulesByContributesAndroidInjectorInModule(
      Elements elements, Messager messager, TypeElement m) {
    Set<TypeElement> result = new HashSet<>();
    for (Element e : elements.getAllMembers(m)) {
      if (!isContributesAndroidInjectorMethod(e)) {
        continue;
      }
      // logger.n("element: %s", e);
      ExecutableElement method = (ExecutableElement) e;
      String canonicalName = getModuleNameFromContributesAndroidInjectorMethod(m, method);
      // logger.n(canonicalName);
      TypeElement typeElement = elements.getTypeElement(canonicalName);
      if (typeElement == null) {
        return null;
      }
      result.add(typeElement);
    }
    return result;
  }

  @Nullable
  public static Set<DependencyInfo> getDependencyInfosHandlingBox(
      SetMultimap<BindingKey, DependencyInfo> dependencies, BindingKey key) {
    Set<DependencyInfo> dependencyInfos = null;
    if (dependencies.containsKey(key)) {
      Preconditions.checkState(!dependencies.get(key).isEmpty(),
          "empty value for for key: " + key);
      dependencyInfos = dependencies.get(key);
    } else if (key.getTypeName().isBoxedPrimitive() || key.getTypeName().isPrimitive()) {
      key = key.boxOrUnbox();
      if (dependencies.containsKey(key)) {
        Preconditions.checkState(
            !dependencies.get(key).isEmpty(), "empty value for for key: " + key);
        dependencyInfos = dependencies.get(key);
      }
    }

    return dependencyInfos;
  }

  public String getMethodNameCallingMethod(TypeElement cls,
      ExecutableElement method) {
    return "call_" + getSourceCodeName(cls) + "_" + method.getSimpleName();
  }

  public boolean isStatic(ExecutableElement method) {
    return method.getModifiers().contains(Modifier.STATIC);
  }

  public TypeElement findBuilder(Elements elements, TypeElement eitherComponent) {
    for (Element e : elements.getAllMembers(eitherComponent)) {
      if (isEitherComponentBuilder(e)) {
        return (TypeElement) e;
      }
    }
    return null;
  }

  public <T> Set<T> getTypedElements(
      RoundEnvironment env, Class<? extends Annotation>... cls) {
    Set<T> result = new HashSet<>();
    for (Class<? extends Annotation> c : cls) {
      result.addAll(
          Collections2.transform(
              env.getElementsAnnotatedWith(c),
              from -> {
                return (T) from;
              }));
    }
    return result;
  }

  public BindingKey getKeyForField(DeclaredType parentType, Element element) {
    return BindingKey.get(types.asMemberOf(parentType, element), getQualifier(element));
  }

  public List<BindingKey> getCtorDependencies(
      SetMultimap<BindingKey, DependencyInfo> dependencies,
      BindingKey key) {
    TypeElement cls = getClassFromKey(key);
    DeclaredType clsType = (DeclaredType) getTypeFromKey(key);

    ExecutableElement ctor = findInjectedCtor(cls);
    Preconditions.checkNotNull(ctor, String.format("Did not find ctor for %s", cls));
    ExecutableType ctorType = (ExecutableType) types.asMemberOf(clsType, ctor);
    List<BindingKey> dependencyKeys = getDependenciesFromMethod(ctorType, ctor);
    return dependencyKeys;
  }

  // Handles generic case.
  public TypeElement getClassFromKey(BindingKey key) {
    ClassName className = getClassNameFromKey(key);
    String classNameString = getClassCanonicalName(className);
    TypeElement result = elements.getTypeElement(classNameString);
    Preconditions.checkNotNull(result,
        String.format("Did not find TypeElement for %s", classNameString));
    return result;
  }

  public ClassName getClassNameFromKey(BindingKey key) {
    ClassName className;
    TypeName typeName = key.getTypeName();
    if (typeName instanceof ClassName) {
      className = (ClassName) typeName;
    } else {
      Preconditions.checkState(typeName instanceof ParameterizedTypeName,
          String.format("typeName: %s", typeName));
      className = ((ParameterizedTypeName) typeName).rawType;
    }
    return className;
  }

  public boolean isAbstract(Element element) {
    return element.getModifiers().contains(Modifier.ABSTRACT);
  }

  public String getMethodNameSettingField(TypeElement cls, VariableElement field) {
    return "set" + cls.getSimpleName() + "_" + field.getSimpleName();
  }

  public boolean isModule(Element element) {
    return Sets.newHashSet(ElementKind.CLASS, ElementKind.INTERFACE).contains(element.getKind())
        && hasAnnotationMirror(element, Module.class);
  }

  public boolean isCtor(Element e) {
    return e.getKind().equals(ElementKind.CONSTRUCTOR);
  }

  public boolean isScoped(@Nullable DependencyInfo dependencyInfo) {
    if (dependencyInfo == null) {
      return false;
    }
    boolean result;
    Set<DeclaredType> scopeTypes = null;
    switch (dependencyInfo.getDependencySourceType()) {
      case MODULE:
        scopeTypes = getScopeTypes(dependencyInfo.getProvisionMethodElement());
        break;
      case CTOR_INJECTED_CLASS:
        scopeTypes = getScopeTypes(dependencyInfo.getSourceClassElement());
        break;
      default:
        result = false;
    }
    if (scopeTypes == null || scopeTypes.isEmpty()) {
      result = false;
    } else {
      DeclaredType scopeType = Iterables.getOnlyElement(scopeTypes);

      result = !isReusableScope(scopeType);
    }
    return result;
  }

  /**
   * Returns true is the type is {@link java.lang.reflect.ParameterizedType} and has {@link
   * javax.lang.model.type.TypeVariable}.
   */
  public boolean isNotSpecializedGeneric(TypeMirror type) {
    // logger.n("type: %s", type);
    switch (type.getKind()) {
      case TYPEVAR:
        return true;
      case DECLARED:
        for (TypeMirror i : ((DeclaredType) type).getTypeArguments()) {
          if (isNotSpecializedGeneric(i)) {
            return true;
          }
        }
        break;
      case WILDCARD:
        WildcardType wildcardType = (WildcardType) type;
        TypeMirror extendsBound = wildcardType.getExtendsBound();
        if (extendsBound != null && isNotSpecializedGeneric(extendsBound)) {
          return true;
        }
        TypeMirror superBound = wildcardType.getSuperBound();
        if (superBound != null && isNotSpecializedGeneric(superBound)) {
          return true;
        }
        break;
      default:
        break;
    }
    return false;
  }

  public void findDirectModules(Set<TypeElement> modules, Element component) {
    AnnotationMirror componentAnnotationMirror = isComponent(component) ?
        getAnnotationMirror(component, Component.class) :
        getAnnotationMirror(component, Subcomponent.class);
    AnnotationValue moduleAnnotationValue = Utils
        .getAnnotationValue(elements, componentAnnotationMirror, "modules");
    if (moduleAnnotationValue != null) {
      for (AnnotationValue annotationValue :
          (List<AnnotationValue>) moduleAnnotationValue.getValue()) {
        modules.add((TypeElement) ((DeclaredType) annotationValue.getValue()).asElement());
      }
    }
  }

  public Set<TypeElement> findAllModulesOfComponentRecursively(TypeElement component) {
    Set<TypeElement> result = new HashSet<>();
    findDirectModules(result, component);
    result = findAllModulesRecursively(result, elements);
    return result;
  }

  public boolean isNotSpecializedGeneric(BindingKey key) {
    return isNotSpecializedGeneric(key.getTypeName());
  }
    /**
     * Returns true is the type is {@link ParameterizedTypeName} and has {@link TypeVariableName}.
     */
  public boolean isNotSpecializedGeneric(TypeName type) {
    // logger.n("typeName: %s", type);
    if (type instanceof TypeVariableName) {
      return true;
    }
    if (type instanceof ParameterizedTypeName) {
      for (TypeName i : ((ParameterizedTypeName) type).typeArguments) {
        if (isNotSpecializedGeneric(i)) {
          return true;
        }
      }
    }
    if (type instanceof WildcardTypeName) {
      WildcardTypeName wildcardTypeName = (WildcardTypeName) type;
      for (TypeName i : wildcardTypeName.upperBounds) {
        if (isNotSpecializedGeneric(i)) {
          return true;
        }
      }
      for (TypeName i : wildcardTypeName.lowerBounds) {
        if (isNotSpecializedGeneric(i)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns true is the type includes only public types.
   */
  public boolean isPublicRecurively(TypeMirror type) {
    switch (type.getKind()) {
      case TYPEVAR:
        throw new RuntimeException("non-specilized type found: " + type);
      case DECLARED:
        if (!isPublicAndAncestor((TypeElement) ((DeclaredType) type).asElement())) {
          return false;
        }
        for (TypeMirror i : ((DeclaredType) type).getTypeArguments()) {
          if (!isPublicRecurively(i)) {
            return false;
          }
        }
        break;
      case WILDCARD:
        WildcardType wildcardType = (WildcardType) type;
        TypeMirror extendsBound = wildcardType.getExtendsBound();
        if (extendsBound != null && !isPublicRecurively(extendsBound)) {
          return false;
        }
        TypeMirror superBound = wildcardType.getSuperBound();
        if (superBound != null && !isPublicRecurively(superBound)) {
          return false;
        }
        break;
      default:
        break;
    }
    return true;
  }

  public boolean isPublicRecurively(TypeName type) {
    if (type.isPrimitive()) {
      return true;
    } else if (type instanceof ClassName) {
      return isPublic(getTypeElement(type));
    } else if (type instanceof ParameterizedTypeName) {
      ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) type;
      if (!isPublic(getTypeElement(parameterizedTypeName.rawType))) {
        return false;
      }
      for (TypeName i : parameterizedTypeName.typeArguments) {
        if (!isPublicRecurively(i)) {
          return false;
        }
      }
      return true;
    } else if (type instanceof WildcardTypeName) {
      for (TypeName i : ((WildcardTypeName) type).lowerBounds) {
        if (!isPublicRecurively(i)) {
          return false;
        }
      }
      for (TypeName i : ((WildcardTypeName) type).upperBounds) {
        if (!isPublicRecurively(i)) {
          return false;
        }
      }
      return true;
    } else {
      throw new RuntimeException("unexpected type: " + type);
    }
  }


  public void trimTrailing(StringBuilder stringBuilder, String s) {
    if (stringBuilder.substring(stringBuilder.length() - s.length()).equals(s)) {
      stringBuilder.delete(stringBuilder.length() - s.length(), stringBuilder.length());
    }
  }

  /**
   * Return the full qualified name for injector from generic ctor injected class.
   */
  public String getGenericInjectorName(BindingKey key) {
    String packageString = getPackageString(getClassFromKey(key));
    return packageString + "." + getGenericInjectorSimpleName(key);
  }

  public String getGenericInjectorSimpleName(BindingKey key) {
    return "GI" + getSourceCodeName(key);
  }

  /**
   * Returns whether the type and its ancestor are all public.
   */
  public boolean isPublicAndAncestor(TypeElement typeElement) {
    while (typeElement != null) {
      if (!typeElement.getModifiers().contains(Modifier.PUBLIC)) {
        return false;
      }
      Element enclosingElement = typeElement.getEnclosingElement();
      typeElement =
          enclosingElement.getKind().equals(ElementKind.PACKAGE)
              ? null
              : (TypeElement) enclosingElement;
    }
    return true;
  }

  public boolean isPublic(TypeElement typeElement) {
    return typeElement.getModifiers().contains(Modifier.PUBLIC);
  }

  public String getProvisonMethodNameForMultiBindingContributor(DependencyInfo dependencyInfo) {
    return getSourceCodeName(dependencyInfo.getSourceClassElement())
        + "__"
        + dependencyInfo.getProvisionMethodElement().getSimpleName();
  }

  public <T> Set<T> uniteSets(Set<T>... sets) {
    Set<T> result = new HashSet<>();
    for (Set<T> s : sets) {
      result.addAll(s);
    }
    return result;
  }

  public List<StackTraceElement> getStack() {
    return Lists.newArrayList(new Exception().getStackTrace());
  }

  public void logStackAsError() {
    for (StackTraceElement i : getStack()) {
      logger.e(i.toString());
    }
  }

  /**
   * Returns dependencies of the give component, empty set if none.
   */
  public Set<TypeElement> getComponentDependencies(TypeElement component) {
    Set<TypeElement> result = new HashSet<>();
    AnnotationMirror componentAnnotationMirror =
        Preconditions.checkNotNull(getAnnotationMirror(component, Component.class),
            String.format("@Component not found for %s", component));

    AnnotationValue dependenciesAnnotationValue = getAnnotationValue(
        elements, componentAnnotationMirror, COMPONANT_ANNOTATION_ELEMENT_DEPENDENCIES);
    if (dependenciesAnnotationValue == null) {
      return result;
    }
    List<? extends AnnotationValue> dependencies =
        (List<? extends AnnotationValue>) dependenciesAnnotationValue.getValue();
    for (AnnotationValue dependency : dependencies) {
      result.add((TypeElement) ((DeclaredType) dependency.getValue()).asElement());
    }
    return result;
  }

  /**
   * Return Builder of the given (sub)component, null if not found.
   */
  public TypeElement getEitherComonentBuilder(TypeElement eitherComponent) {
    for (Element e : elements.getAllMembers(eitherComponent)) {
      if (isEitherComponentBuilder(e)) {
        return (TypeElement) e;
      }
    }
    return null;
  }

  public Set<BindingKey> getBindsInstances(TypeElement eitherComponent) {
    Set<BindingKey> result = new HashSet<>();
    TypeElement builder = getEitherComonentBuilder(eitherComponent);
    if (builder != null) {
      for (Element e : elements.getAllMembers(builder)) {
        if (isBindsInstanceMethod(e)) {
          result.add(getKeyForOnlyParameterOfMethod(types, (DeclaredType) (builder.asType()), e));
        }
      }
    }
    return result;
  }

  public Set<TypeName> collectPackagedHubInterfaces(TypeElement eitherComponent,
      SetMultimap<BindingKey, DependencyInfo> dependencies) {
    Set<TypeName> result = collectDirectPackagedHubInterfaces(eitherComponent, dependencies);
    addRecursivePackagedHubInterfaces(result);
    logger.n("packagedHubInterfaces: %s", result);
    return result;
  }

  public Set<TypeName> collectDirectPackagedHubInterfaces(TypeElement eitherComponent,
      SetMultimap<BindingKey, DependencyInfo> dependencies) {
    Set<TypeName> result = new HashSet<>();
    Set<Element> ctorInjected = new HashSet<>();
    Set<TypeElement> modules = new HashSet<>();
    for (DependencyInfo dependencyInfo : dependencies.values()) {
      switch(dependencyInfo.getDependencySourceType()) {
        case CTOR_INJECTED_CLASS:
          ctorInjected.add(dependencyInfo.getSourceClassElement());
          break;
        case MODULE:
          modules.add(dependencyInfo.getSourceClassElement());
          break;
        case DAGGER_MEMBERS_INJECTOR:
          break;
        case COMPONENT_DEPENDENCIES_METHOD:
          break;
        case COMPONENT_DEPENDENCIES_ITSELF:
          break;
        case EITHER_COMPONENT:
          break;
        case EITHER_COMPONENT_BUILDER:
          break;
        case BINDS_INTANCE:
          break;
        case NONE:
          break;
      }
    }
    Set<String> packages = getPackages(modules, ctorInjected);
    for (BindingKey key : getProduced(eitherComponent).second) {
      TypeElement typeElement = getTypeElement(key);
      // TypeElement typeElement = utils.getClosestInjectedAncestor(utils.getTypeElement(key));
      if (typeElement != null) {
        String packageString = getPackageString(typeElement);
        logPackageSource(typeElement, packageString);
        packages.add(packageString);
      }
    }
    for (String p : packages) {
      result.add(ClassName.get(p, PackagedHubInterfaceGenerator.HUB_INTERFACE));
    }
    return result;
  }

  public void addRecursivePackagedHubInterfaces(Set<TypeName> result) {
    Set<TypeName> work = new HashSet<>(result);
    while (!work.isEmpty()) {
      TypeName typeName =
          Preconditions.checkNotNull(Iterables.getFirst(work, null), "work: " + work);
      work.remove(typeName);

      TypeElement packagedHubInterface = getTypeElement(typeName);
      if (packagedHubInterface == null) {
        logger.e("typeName not found: %s", typeName);
        continue;
      }
      traverseAndDo(
          types,
          (DeclaredType) packagedHubInterface.asType(),
          packagedHubInterface,
          pair -> {
            ExecutableElement e = (ExecutableElement) pair.second;
            TypeElement produced;
            if (isProvisionMethodInInjector(e)) {
              return null;
              // TODO: those provided by module cause trouble, e.g., Fragment.
              // produced = utils.getReturnTypeElement(e);
            } else {
              Preconditions.checkState(isInjectionMethod(e), "element: " + e);
              TypeMirror typeMirror =
                  Iterables.getOnlyElement(((ExecutableType) pair.first).getParameterTypes());
              produced = (TypeElement) ((DeclaredType) typeMirror).asElement();
            }
            String packageString = getPackage(produced).getQualifiedName().toString();
            logPackageSource(produced, packageString);
            TypeName newPackagedHubInterface = ClassName.get(
                packageString,
                PackagedHubInterfaceGenerator.HUB_INTERFACE);
            if (result.add(newPackagedHubInterface)) {
              // logger.w("added: %s", newPackagedHubInterface);
              work.add(newPackagedHubInterface);
            }
            return null;
          });
    }
  }

  public Pair<Set<BindingKey>, Set<BindingKey>> getProduced(TypeElement eitherComponent) {
    Set<BindingKey> provided = new HashSet<>();
    Set<BindingKey> injected = new HashSet<>();

    // Set<String> s = new HashSet<>();
    // s.add("PartyHatPromoController");
    // s.add("ReviewAtAPlaceNotificationAdapterRecommend");
    // s.add("ReviewAtAPlaceNotificationAdapterStars");

    DeclaredType eitherComponentType = (DeclaredType) eitherComponent.asType();
    traverseAndDo(
        types,
        eitherComponentType,
        eitherComponent,
        p -> {
          Element element = p.second;
          // logger.n(element.toString());
          if (!element.getKind().equals(ElementKind.METHOD)) {
            return null;
          }
          ExecutableElement method = (ExecutableElement) element;
          ExecutableType methodType =
              (ExecutableType)
                  processingEnvironment.getTypeUtils().asMemberOf(eitherComponentType, method);
          // Injection methods.
          if (isInjectionMethod(element)) {
            TypeMirror typeMirror = Iterables.getOnlyElement(methodType.getParameterTypes());
            while (typeMirror != null) {
              if (!((DeclaredType) typeMirror).getTypeArguments().isEmpty()) {
                logger.w("Inject generic type: %s", typeMirror);
                typeMirror = types.erasure(typeMirror);
              }
              injected.add(BindingKey.get(typeMirror));
              TypeElement closestInjectedAncestor =
                  getClosestInjectedAncestor((TypeElement) ((DeclaredType) typeMirror).asElement());
              typeMirror =
                  closestInjectedAncestor == null ? null : closestInjectedAncestor.asType();
            }

            // if (typeMirror instanceof DeclaredType) {
            //   String qN =
            //       ((TypeElement) ((DeclaredType) typeMirror).asElement())
            //           .getQualifiedName()
            //           .toString();
            //   for (String i : s) {
            //     if (qN.contains(i)) {
            //       for (StackTraceElement j : utils.getStack()) {
            //         logger.e("%s.%s", method.getEnclosingElement(), method);
            //       }
            //     }
            //   }
            // }

          } else if (isProvisionMethodInInjector(element)) {
            provided.add(getKeyProvidedByMethod(method));
          } else if (isIrrelevantMethodInInjector(element)) {
            // do nothing
          } else {
            logger.l(
                Kind.WARNING, "Element %s ignored from injector %s.", element, eitherComponentType);
          }
          return null;
        });
    // addFromComponentDependencies(provided);
    // addBindsInstance(provided);
    // addFromPackagedHubInterfaces(provided);
    // addFromSubcomponentHubInterfaces(provided);
    logger.n("provided: %s\ninjected: %s", provided, injected);
    return Pair.of(provided, injected);
  }

  // Returns if the element is a method irrelevant to injection.
  public final boolean isIrrelevantMethodInInjector(Element element) {
    return !Utils.isMethod(element) || !element.getModifiers().contains(Modifier.ABSTRACT);
  }

  public void generateDebugInfoMethod(Builder injectorBuilder, String methodName) {
    generateDebugInfoMethod(injectorBuilder, methodName, "");
  }

  public void generateDebugInfoMethod(
      Builder injectorBuilder, String methodName, String annotationValue) {
    // if (true) {
    //   return;
    // }
    injectorBuilder.addMethod(
        MethodSpec.methodBuilder(methodName.replace(".", "_") + "_" + System.nanoTime())
            .addAnnotation(
                AnnotationSpec.builder(Generated.class)
                    .addMember("value", "$S", annotationValue)
                    .build())
            .build());
  }

  public void generateAbstractProvisonMethodIfNeeded(
      Builder interfaceBuilder, String methodName, BindingKey key, Set<BindingKey> done) {
    if (!done.add(key)) {
      return;
    }
    MethodSpec.Builder builder =
        MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
            .returns(key.getTypeName());
    if (key.getQualifier() != null) {
      builder.addAnnotation(key.getQualifier());
    }
    interfaceBuilder.addMethod(builder.build());
  }


}
