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

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;

import dagger.MapKey;
import dagger.MembersInjector;
import dagger.Module;
import dagger.Provides;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic.Kind;

/**
 * Collects all the dagger dependencies from a give {@link Module}s and Classes
 * with injected ctor. The collected information can be used later, e.g.,
 * verification or code generation. See details of dagger here:
 * http://google.github.io/dagger/.
 */
class NewDependencyCollector {
  private static final String TAG = "NewDependencyCollector";

  private final ProcessingEnvironment env;
  private final Messager messager;

  private List<String> errors = new ArrayList<>();

  public NewDependencyCollector(ProcessingEnvironment env) {
    this.env = env;
    this.messager = env.getMessager();
  }

  /**
   * Returns all dagger dependencies for all the binding required by the give modules and
   * injectedClasses.
   */
  public Collection<NewDependencyInfo> collect(
      Collection<TypeElement> modules,
      Collection<TypeElement> memberInjectors,
      List<String> allErrors) {

    /** Keys for generic bindings are the raw type to be searchable. */
    SetMultimap<NewBindingKey, NewDependencyInfo> result = HashMultimap.create();

    modules = Utils.findAllModulesRecursively(modules);
    for (TypeElement e : modules) {
      Collection<NewDependencyInfo> dependencies = collectFromModule(e);
      checkDependencies(result, dependencies);
      addDependencyInfo(result, dependencies);
    }
  //messager.printMessage(Kind.NOTE, String.format("collect() after from modules: all: %s", result));

    memberInjectors = findAllMembersInjectorsRecursively(memberInjectors);
    Set<NewBindingKey> requiredKeys = getRequiredKeys(memberInjectors, result);

    addDependenciesForRequiredKeys(result, requiredKeys);

  // messager.printMessage(Kind.NOTE, String.format("collect(): all: %s", result));
    allErrors.addAll(errors);
    
    return result.values();
  }

  /**
   * Add dependencies from ctor injected classes needed by requiredKeys recursively to result.
   */
  private void addDependenciesForRequiredKeys(
      SetMultimap<NewBindingKey, NewDependencyInfo> result, Set<NewBindingKey> requiredKeys) {
    // Added all the required dependencies from ctor injected classes.
    while (!requiredKeys.isEmpty()) {
      NewBindingKey key = Iterables.getFirst(requiredKeys, null);
      Preconditions.checkNotNull(key);
      requiredKeys.remove(key);
      if (result.containsKey(key)) {
        continue;
      }

      TypeName typeName = key.getTypeName();
      if (Utils.hasBuiltinBinding(typeName)) {
        key = Utils.getElementKeyForBuiltinBinding(key);
        requiredKeys.add(key);
        continue;
      }

      if (Utils.isMapWithBuiltinValueType(key)) {
        NewBindingKey peeledMapKey = Preconditions.checkNotNull(Utils.peelMapWithBuiltinValue(key));
        requiredKeys.add(peeledMapKey);
        continue;
      }
      
      ClassName className;
      if (typeName instanceof ClassName) {
        className = (ClassName) typeName;
      } else {
        Preconditions.checkState(
            typeName instanceof ParameterizedTypeName,
            "Expect a %s but get %s",
            ParameterizedTypeName.class,
            typeName);
        ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
        for (TypeName parameter : parameterizedTypeName.typeArguments) {
          Preconditions.checkState(
              parameter instanceof ClassName || parameter instanceof ParameterizedTypeName,
              String.format("Unexpected parameter type %s for type %s.", parameter, typeName));
        }
        NewDependencyInfo dependencyInfo = Utils.getDependencyInfoByGeneric(result, key);
        if (dependencyInfo != null) {
          requiredKeys.addAll(dependencyInfo.getDependencies());
          continue;
        } else {
          className = ((ParameterizedTypeName) typeName).rawType;
        }
      }
      TypeElement classTypeElement =
          env.getElementUtils().getTypeElement(Utils.getClassCanonicalName(className));
      Preconditions.checkNotNull(classTypeElement, String.format("Class %s not found.", className));
      Collection<NewDependencyInfo> dependencies = collectFromCtorInjectedClass(classTypeElement);
      if (dependencies == null) {
        messager.printMessage(Kind.ERROR, String.format("Binding not found for %s", key));
        continue;
      }
      NewDependencyInfo dependency = Iterables.getOnlyElement(dependencies);
      if (typeName instanceof ParameterizedTypeName) {
        Map<TypeVariableName, TypeName> parameterMap =
            Utils.getMapFromTypeVariableToSpecialized(
                (ParameterizedTypeName) typeName,
                (ParameterizedTypeName) dependency.getDependant().getTypeName());
        requiredKeys.addAll(Utils.specializeIfNeeded(dependency.getDependencies(), parameterMap));
      } else {
        requiredKeys.addAll(dependency.getDependencies());
      }
      checkOneDependency(result, dependency);
      addDependencyInfo(result, dependency);
    }
  }

  /**
   * Returns all the {@link NewBindingKey} required by give dependencyInfos and injectors
   * recursively.
   */
  public Set<NewBindingKey> getRequiredKeys(
      Collection<TypeElement> membersInjectors, Collection<NewDependencyInfo> dependencyInfos) {
    Set<NewBindingKey> result =
        getRequiredKeys(membersInjectors, collectionToMultimap(dependencyInfos));

    return result;
  }

  /**
   * Returns {@link NewBindingKey}s required by the give injected classes and dependency
   * infos(specifically, that dependencies, not the dependant).
   */
  private Set<NewBindingKey> getRequiredKeys(
      Collection<TypeElement> membersInjectors,
      SetMultimap<NewBindingKey, NewDependencyInfo> dependencyInfos) {
    Set<NewBindingKey> requiredKeys = new HashSet<>();
    Set<TypeElement> allMembersInjectors = findAllMembersInjectorsRecursively(membersInjectors);
    for (TypeElement membersInjector : allMembersInjectors) {
      requiredKeys.addAll(getRequiredKeys(membersInjector));
    }

    for (NewDependencyInfo info : dependencyInfos.values()) {
      for (NewBindingKey key : info.getDependencies()) {
        if (requiredKeys.contains(key)) {
          continue;
        }
        if (Utils.isBindable(key.getTypeName())) {
          requiredKeys.add(key);
        }
      }
    }

    return requiredKeys;
  }

  /**
   * Returns all the required {@link NewBindingKey}s by the provision methods and injection methods
   * included in the given class directly.
   */
  private Set<NewBindingKey> getRequiredKeys(TypeElement membersInjector) {
    Set<NewBindingKey> result = new HashSet<>();
    for (Element element : membersInjector.getEnclosedElements()) {
      if (!element.getKind().equals(ElementKind.METHOD)) {
        continue;
      }
      ExecutableElement method = (ExecutableElement) element;
      if (isInjectorProvisionMethod(method)) {
        result.add(
            NewBindingKey.get(method.getReturnType(), Utils.getQualifier(method)));
      }
      if (isInjectorInjectionMethod(method)) {
        TypeMirror typeMirror = Iterables.getOnlyElement(method.getParameters()).asType();
        if (typeMirror.getKind().equals(TypeKind.TYPEVAR)) {
          // TODO(freeman): supported inherit from generic interface, e.g.,
          // java/com/google/devtools/moe/client:Moe.
          continue;
        }
        TypeElement injectedClass =
            (TypeElement)
                ((DeclaredType) Iterables.getOnlyElement(method.getParameters()).asType())
                    .asElement();
        Collection<NewDependencyInfo> dependencyInfos = collectFromInjectedClass(injectedClass);
        result.addAll(Iterables.getOnlyElement(dependencyInfos).getDependencies());
      }
    }
    return result;
  }

  /**
   * Returns if the given {@link ExecutableElement} is a provision method in a
   * {@link MembersInjector}.
   */
  private boolean isInjectorProvisionMethod(ExecutableElement method) {
    return method.getParameters().isEmpty() && Utils.isBindableType(method.getReturnType());
  }

  /**
   * Returns if the given {@link ExecutableElement} is a injection method in a
   * {@link MembersInjector}.
   */
  private boolean isInjectorInjectionMethod(ExecutableElement method) {
    return method.getParameters().size() == 1
        && method.getReturnType().getKind().equals(TypeKind.VOID);
  }

  /**
   * Returns all the injected classes and them super interfaces.
   */
  private static Set<TypeElement> findAllMembersInjectorsRecursively(
      Collection<TypeElement> membersInjectors) {

    Set<TypeElement> result = new HashSet<>();

    for (TypeElement element : membersInjectors) {
      result.addAll(findAllMembersInjectorsRecursively(element));
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private static Set<TypeElement> findAllMembersInjectorsRecursively(TypeElement membersInjector) {
    Set<TypeElement> result = new HashSet<>();

    result.add(membersInjector);

    for (TypeMirror mirror : membersInjector.getInterfaces()) {
      result.addAll(
          findAllMembersInjectorsRecursively((TypeElement) ((DeclaredType) mirror).asElement()));
    }
    return result;
  }

  /**
   * NOTE: the key of the returned map is of the raw type if the related
   * {@link NewDependencyInfo#getDependant()} is for a generic class.
   */
  public static SetMultimap<NewBindingKey, NewDependencyInfo> collectionToMultimap(
      Collection<NewDependencyInfo> dependencies) {
    SetMultimap<NewBindingKey, NewDependencyInfo> result = HashMultimap.create();
    for (NewDependencyInfo info : dependencies) {
      NewBindingKey key = info.getDependant();
      TypeName typeName = key.getTypeName();
      // For generic type with type variable, only keep raw type.
      if (typeName instanceof ParameterizedTypeName) {
        ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
        TypeName anyParameter =
            Preconditions.checkNotNull(
                Iterables.getFirst(parameterizedTypeName.typeArguments, null),
                String.format("ParameterizedTypeName of %s has no parameter.", key));
        if (anyParameter instanceof TypeVariableName) {
          typeName = parameterizedTypeName.rawType;
          key = NewBindingKey.get(typeName, key.getQualifier());
        }
      }

      result.put(key, info);
    }
    return result;
  }

  private void addDependencyInfo(
      SetMultimap<NewBindingKey, NewDependencyInfo> existingDependencies,
      Collection<NewDependencyInfo> newDependencies) {
    for (NewDependencyInfo info : newDependencies) {
      if (!addDependencyInfo(existingDependencies, info)) {
        errors.add(String.format("Adding dependency failed for %s.", info));
      }
    }
  }

  /**
   * Adds the give {@link NewDependencyInfo} to the map, handling generic type with formal
   * parameters. Returns if it changed the given map.
   */
  private boolean addDependencyInfo(
      SetMultimap<NewBindingKey, NewDependencyInfo> existingDependencies, NewDependencyInfo info) {
    NewBindingKey key = info.getDependant();
    TypeName typeName = key.getTypeName();
    // For generic type with type variable, only keep raw type.
    if (typeName instanceof ParameterizedTypeName) {
      ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
      TypeName anyParameter =
          Preconditions.checkNotNull(
              Iterables.getFirst(parameterizedTypeName.typeArguments, null),
              String.format("ParameterizedTypeName of %s has no parameter.", key));
      if (anyParameter instanceof TypeVariableName) {
        typeName = parameterizedTypeName.rawType;
        key = NewBindingKey.get(typeName, key.getQualifier());
      }
    }

    return existingDependencies.put(key, info);
  }
  /*
   * Checks if the new dependencies can be added to the existing ones.
   * Dependency Key should not appear more than once.
   */
  private void checkDependencies(
      SetMultimap<NewBindingKey, NewDependencyInfo> existingDependencies,
      Collection<NewDependencyInfo> newDependencies) {
    for (NewDependencyInfo info : newDependencies) {
      checkOneDependency(existingDependencies, info);
    }
  }

  private void checkOneDependency(
      SetMultimap<NewBindingKey, NewDependencyInfo> existingDependencies,
      NewDependencyInfo newInfo) {
    Preconditions.checkNotNull(newInfo);
    if (existingDependencies.containsKey(newInfo.getDependant())) {
      Set<NewDependencyInfo> dependencyInfoSet = existingDependencies.get(newInfo.getDependant());
      NewDependencyInfo existingDependencyInfo =
          Preconditions.checkNotNull(Iterables.getFirst(dependencyInfoSet, null));
      if (existingDependencyInfo.getType().equals(Provides.Type.UNIQUE)
          || newInfo.getType().equals(Provides.Type.UNIQUE)) {
        String error =
            String.format(
                "Adding dependencies failed.%n %s%nAlready existing: %s", newInfo, dependencyInfoSet);
        errors.add(error);
      }
    }
  }

  /**
   * Collects dependencies from a given {@link dagger.Module}. Type.SET and
   * Type.SET_VALUES are put together with Key.get(Set<elementType>, annotation)
   * for easier later processing.
   */
  private Collection<NewDependencyInfo> collectFromModule(TypeElement module) {
    Collection<NewDependencyInfo> result = new HashSet<>();
    for (Element e : module.getEnclosedElements()) {
      if (!Utils.isProvidesMethod(e, env)) {
        continue;
      }

      ExecutableElement executableElement = (ExecutableElement) e;
      TypeMirror returnType = executableElement.getReturnType();
      TypeKind returnTypeKind = returnType.getKind();
      Preconditions.checkState(
          returnTypeKind.isPrimitive() || returnTypeKind.equals(TypeKind.DECLARED)
              || returnTypeKind.equals(TypeKind.ARRAY),
          String.format("Unexpected type %s from method %s in module %s.", returnTypeKind,
              executableElement, module));

      if (!Utils.isBindableType(returnType)) {
        errors.add(
            String.format(
                "Unbindable type found: %s from module %s by method %s",
                returnType,
                module,
                executableElement));
      }

      AnnotationMirror annotation = Utils.getQualifier(executableElement);
      NewBindingKey key = NewBindingKey.get(returnType, annotation);

      List<NewBindingKey> keys = Utils.getDependenciesFromExecutableElement(executableElement);

      Provides.Type provideType = Utils.getProvidesType(executableElement);
      if (Provides.Type.SET.equals(provideType)) {
        key =
            NewBindingKey.get(
                ParameterizedTypeName.get(ClassName.get(Set.class), key.getTypeName()), annotation);
      } else if (Provides.Type.MAP.equals(provideType)) {
        AnnotationMirror mapKeyedMirror = Preconditions.checkNotNull(
            Utils.getMapKey(executableElement),
            String.format("Map binding %s missed MapKey.", executableElement));
        AnnotationMirror mapKeyMirror =
            Utils.getAnnotationMirror(mapKeyedMirror.getAnnotationType().asElement(), MapKey.class);
        AnnotationValue unwrapValue = Utils.getAnnotationValue(mapKeyMirror, "unwrapValue");
        if (unwrapValue != null && !((Boolean) unwrapValue.getValue())) {
          messager.printMessage(Kind.ERROR,
              String.format("MapKey with unwrapValue false is not supported, yet. Biding: %s",
                  executableElement));
        }
        TypeMirror keyTypeMirror = Preconditions.checkNotNull(
            Utils.getElementTypeMirror(mapKeyedMirror, "value"),
            String.format("Get key type failed for binding %s", executableElement));
        TypeMirror valueTypeMirror = executableElement.getReturnType();
        AnnotationMirror qualifier = Utils.getQualifier(executableElement);
        key = NewBindingKey.get(
            ParameterizedTypeName.get(ClassName.get(Map.class),
                TypeName.get(keyTypeMirror),
                TypeName.get(valueTypeMirror)), qualifier);
      }
      NewDependencyInfo newDependencyInfo =
          new NewDependencyInfo(key, Sets.newHashSet(keys), module, executableElement, provideType);
      result.add(newDependencyInfo);
    }

//    messager.printMessage(Kind.NOTE, String.format("collectFromModule: result: %s", result));
    return result;
  }

  /**
   * Returns dependency info from ctor injected class, null if such class not found.
   */
  @Nullable
  private Collection<NewDependencyInfo> collectFromCtorInjectedClass(TypeElement classElement) {
    Collection<NewDependencyInfo> result = new HashSet<>();
    Preconditions.checkArgument(
        !Utils.hasAnonymousParentClass(classElement),
        String.format("class %s should not have anonymous ancestor.", classElement));

    ExecutableElement ctor = Utils.findInjectedCtor(classElement, env);
    if (ctor == null) {
      return null;
    }

    Set<NewBindingKey> dependencies = getDependenciesFrom(ctor);

    for (Element element : classElement.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.FIELD) && Utils.isInjected(element, env)) {
        dependencies.add(NewBindingKey.get(element.asType(), Utils.getQualifier(element)));
      }
      if (element.getKind().equals(ElementKind.METHOD) && Utils.isInjected(element, env)) {
        dependencies.addAll(getDependenciesFrom((ExecutableElement) element));
      }
    }

    NewDependencyInfo dependenceInfo =
        new NewDependencyInfo(
            NewBindingKey.get(classElement), dependencies, classElement, Provides.Type.UNIQUE);
    result.add(dependenceInfo);

    return result;
  }

  /**
   * Returns 1 or 0 {@link NewDependencyInfo} from the give class whose is
   * usually provided outside of Dagger and is injected be the app.
   */
  private Set<NewDependencyInfo> collectFromInjectedClass(TypeElement classElement) {
    Preconditions.checkArgument(
        !Utils.hasAnonymousParentClass(classElement),
        String.format("class %s should not be or have anonymous ancestor class.", classElement));

    Set<NewBindingKey> dependencies = collectFromInjectedMembersRecursively(classElement);

    NewDependencyInfo dependenceInfo =
        new NewDependencyInfo(
            NewBindingKey.get(classElement), dependencies, classElement, Provides.Type.UNIQUE);
    Set<NewDependencyInfo> result = Sets.newHashSet(dependenceInfo);

    return result;
  }

  /**
   * Collects dependencies of the give {@link TypeElement} from the injected
   * members, either fields or methods, of the {@link TypeElement}.
   */
  private Set<NewBindingKey> collectFromInjectedMembersRecursively(TypeElement classElement) {
    Preconditions.checkArgument(
        Utils.findInjectedCtor(classElement, env) == null,
        String.format("class %s should not have injected ctor", classElement));

    Set<NewBindingKey> dependencies = new HashSet<>();

    TypeMirror superMirror = classElement.getSuperclass();
    if (!superMirror.getKind().equals(TypeKind.NONE)) {
      TypeElement parent = (TypeElement) ((DeclaredType) superMirror).asElement();
      dependencies = collectFromInjectedMembersRecursively(parent);
    }

    for (Element element : classElement.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.FIELD) && Utils.isInjected(element, env)) {
        dependencies.add(NewBindingKey.get(element.asType(), Utils.getQualifier(element)));
      }
      if (element.getKind().equals(ElementKind.METHOD) && Utils.isInjected(element, env)) {
        dependencies.addAll(getDependenciesFrom((ExecutableElement) element));
      }
    }

    return dependencies;
  }

  private Set<NewBindingKey> getDependenciesFrom(ExecutableElement element) {
    Set<NewBindingKey> result = new HashSet<>();
    for (Element parameter : element.getParameters()) {
      result.add(NewBindingKey.get(parameter.asType(), Utils.getQualifier(parameter)));
    }
    return result;
  }
}
