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
import static tiger.ProvisionType.UNIQUE;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import dagger.MapKey;
import dagger.Module;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * Collects all the dagger dependencies from a give {@link Module}s and Classes
 * with injected ctor. The collected information can be used later, e.g.,
 * verification or code generation. See details of dagger here:
 * http://google.github.io/dagger/.
 */
public class DependencyCollector {
  private static final String TAG = "DependencyCollector";

  private static DependencyCollector instance;
  private final ProcessingEnvironment env;
  private final Messager messager;
  private final Elements elements;
  private final Types types;
  private final Utils utils;

  private final Logger logger;
  private List<String> errors = new ArrayList<>();
  private final Map<TypeElement, Collection<DependencyInfo>> eitherComponentToDependenciesMap =
      new HashMap<>();

  public static DependencyCollector getInstance(ProcessingEnvironment env, Utils utils) {
    if (instance == null) {
      instance = new DependencyCollector(env, utils);
    }
    return instance;
  }

  private DependencyCollector(ProcessingEnvironment env, Utils utils) {
    this.env = env;
    elements = env.getElementUtils();
    types = env.getTypeUtils();
    this.messager = env.getMessager();
    this.utils = utils;
    logger = new Logger(messager, Kind.WARNING);
  }

  /**
   * Returns all dagger dependencies for all the binding required by the give modules and
   * injectedClasses.
   *
   * @param components must be the final member injectors instead of their ancester
   *     classes/interfaces so that we can have full context for generic. In dagger, these are the
   *     (sub)components.
   *
   * TODO: map the deps into each core injector even (sub)components so that in different
   * injectors same bind can be provided. See gmm TransitTripGuidance.
   */
  public Collection<DependencyInfo> collect(
      Collection<TypeElement> modules,
      Collection<TypeElement> components,
      Map<TypeElement, CoreInjectorInfo> componentToCoreInjectorMap,
      SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorInfoToComponentDependencyMap,
      SetMultimap<TypeElement, BindingKey> componentToBindsInstanceMap,
      List<String> allErrors) {
    /** Keys for generic bindings are the raw type to be searchable. */
    SetMultimap<BindingKey, DependencyInfo> result = HashMultimap.create();

    modules = utils.findAllModulesRecursively(modules, elements);
    for (TypeElement e : modules) {
      Collection<DependencyInfo> dependencies = collectFromModule(e);
      addDependencyInfo(result, dependencies);
    }

    printMultiMap("collect: result module ", result);

    collectFromComponentDependencies(result, coreInjectorInfoToComponentDependencyMap);
    printMultiMap("collect: result component dependencies ", result);

    collectFromBindsInstance(result, componentToBindsInstanceMap, componentToCoreInjectorMap);

    // logger.n("collect() after from modules: all: %s", result);

    // Debug code below:
    // for (Element component : components) {
    //   TypeMirror superType = ((TypeElement) component).getSuperclass();
    //   messager.printMessage(
    //       Kind.NOTE, TAG + ".getRequiredKeys: component: " + component + " superType " + superType);
    //   for (Element t : elements.getAllMembers((TypeElement) component)) {
    //     TypeMirror subt = types.asMemberOf((DeclaredType) component.asType(), t);
    //     messager.printMessage(
    //         Kind.NOTE,
    //         TAG + ".getRequiredKeys: component: " + component + " element " + t +
    //         "element type: " + subt);
    //   }
    // }

   // memberInjectors = findAllMembersInjectorsRecursively(memberInjectors);
    Set<BindingKey> requiredKeys = getRequiredKeys(components, result);

    printMultiMap("collect: result before ctor ", result);

    logger.n("requiredKeys: %s", requiredKeys);
    completeDependenciesAndRequiredKeys(result, requiredKeys);

    // Not for base/app:application_component.
    // fixDependencies(result);

    printMultiMap("collect: all dependencyInfos", result);
    allErrors.addAll(errors);
    
    return result.values();
  }

  public Collection<DependencyInfo> collectForOne(
      TypeElement eitherComponent,
      @Nullable TypeElement parentEitherComponent,
      Set<BindingKey> unresolved) {
    Set<TypeElement> componentDependencies = utils.isComponent(eitherComponent)
        ? utils.getComponentDependencies(eitherComponent)
        : Sets.newHashSet();
    if (parentEitherComponent != null) {
      componentDependencies.remove(parentEitherComponent);
    }
    return collectForOne(
        eitherComponent,
        parentEitherComponent,
        componentDependencies,
        utils.getBindsInstances(eitherComponent),
        unresolved,
        Lists.newArrayList());
  }

  public Collection<DependencyInfo> collectForOne(
      TypeElement eitherComponent,
      @Nullable TypeElement parentEitherComponent,
      Set<TypeElement> componentDependencies,
      Set<BindingKey> bindsInstances,
      List<String> allRecoverableErrors) {
    return collectForOne(
        eitherComponent,
        parentEitherComponent,
        componentDependencies,
        bindsInstances,
        Sets.newHashSet(),
        allRecoverableErrors);
  }

  public Collection<DependencyInfo> collectForOne(
        TypeElement eitherComponent,
        @Nullable TypeElement parentEitherComponent,
        Set<TypeElement> componentDependencies,
        Set<BindingKey> bindsInstances,
        Set<BindingKey> unresolved,
        List<String> allRecoverableErrors) {
    if (eitherComponentToDependenciesMap.containsKey(eitherComponent)) {
      return eitherComponentToDependenciesMap.get(eitherComponent);
    }
    boolean toDebug = eitherComponent.getSimpleName().contentEquals("ApplicationComponent");
    SetMultimap<BindingKey, DependencyInfo> result = HashMultimap.create();
    Set<TypeElement> modules = utils.findAllModulesOfComponentRecursively(eitherComponent);
    logger.w("modules: %s", modules);
    for (TypeElement e : modules) {
      logger.w("module: %s", e);
      Collection<DependencyInfo> dependencies = collectFromModule(e);
      addDependencyInfo(result, dependencies);
      printMultiMap("collect: result one module ", result);
    }

    printMultiMap("collect: result module ", result);

    logger.n(
        "eitherComponent: %s, componentDependencies: %s", eitherComponent, componentDependencies);
    collectFromComponentDependencies(result, componentDependencies);
    printMultiMap("collect: result component dependencies ", result);

    collectFromBindsInstance(result, eitherComponent, bindsInstances, null);

    logger.n(String.format("collect() after from modules: all: %s",
        result));

    // Debug code below:
    // for (Element component : components) {
    //   TypeMirror superType = ((TypeElement) component).getSuperclass();
    //   messager.printMessage(
    //       Kind.NOTE, TAG + ".getRequiredKeys: component: " + component + " superType " + superType);
    //   for (Element t : elements.getAllMembers((TypeElement) component)) {
    //     TypeMirror subt = types.asMemberOf((DeclaredType) component.asType(), t);
    //     messager.printMessage(
    //         Kind.NOTE,
    //         TAG + ".getRequiredKeys: component: " + component + " element " + t +
    //         "element type: " + subt);
    //   }
    // }

    // memberInjectors = findAllMembersInjectorsRecursively(memberInjectors);
    Set<BindingKey> requiredKeys = getRequiredKeys(Sets.newHashSet(eitherComponent), result);

    printMultiMap("collect: result before ctor ", result);

    logger.n("requiredKeys: %s", requiredKeys);
    // this only completed dependencies, the requiredKeys and unresolved are fixed below.
    // TODO: fix it.
    completeDependenciesAndRequiredKeys(result, requiredKeys, new HashSet<>());
    requiredKeys = getRequiredKeys(Sets.newHashSet(eitherComponent), result);
    for (BindingKey key : requiredKeys) {
      if (utils.getDependencyInfo(result, key) == null) {
        unresolved.add(key);
      }
    }


    // Not for base/app:application_component.
    // fixDependencies(result);

    logger.n("component: %s", eitherComponent);
    printMultiMap("collect: all dependencyInfos", result);
    allRecoverableErrors.addAll(errors);

    logger.w("key count: %d, DI count: %d", result.keySet().size(), result.values().size());
    eitherComponentToDependenciesMap.put(eitherComponent, result.values());
    return result.values();
  }

  // For gmm, replace the component dependency method bindings with ctor injector classes if
  // possible.
  private void fixDependencies(SetMultimap<BindingKey, DependencyInfo> result) {
    Set<BindingKey> keys = new HashSet<>();

    for (BindingKey key : result.keySet()) {
      Set<DependencyInfo> dIs = result.get(key);
      DependencyInfo dI = Preconditions.checkNotNull(Iterables.getFirst(dIs, null));
      if (dI.getDependencySourceType().equals(DependencySourceType.COMPONENT_DEPENDENCIES_METHOD)) {
        Preconditions.checkState(dI.getType().equals(UNIQUE), "expected type for dI: " + dI);
        keys.add(key);
      }
    }

    for (BindingKey key : keys) {
      DeclaredType declaredType = (DeclaredType) utils.getTypeFromKey(key);
      TypeElement typeElement;
      if (key.getTypeName() instanceof ParameterizedTypeName) {
        typeElement =
            utils.getTypeElementForClassName(
                ((ParameterizedTypeName) key.getTypeName()).rawType);
      } else {
        typeElement = utils.getTypeElement(key);
      }
      Collection<DependencyInfo> newDis = collectFromCtorInjectedClass(typeElement, declaredType);
      if (newDis == null) {
        continue;
      }
      Set<DependencyInfo> dIs = result.get(key);
      DependencyInfo newDi = Iterables.getOnlyElement(newDis);
      logger.n(TAG + ".fixDependencies: old dI " + result.get(key));
      dIs.clear();
      dIs.add(newDi);
      logger.n(TAG + ".fixDependencies: new dI " + result.get(key));
    }
  }

  private void collectFromBindsInstance(
      SetMultimap<BindingKey, DependencyInfo> result,
      SetMultimap<TypeElement, BindingKey> componentToBindsInstanceMap,
      Map<TypeElement, CoreInjectorInfo> componentToCoreInjectorMap) {
    for (TypeElement c : componentToBindsInstanceMap.keySet()) {
      collectFromBindsInstance(result, c, componentToBindsInstanceMap.get(c),
          componentToCoreInjectorMap.get(c));
    }
  }

  private void collectFromBindsInstance(SetMultimap<BindingKey, DependencyInfo> result,
      TypeElement component, Set<BindingKey> bindsInstances, CoreInjectorInfo coreInjectorInfo) {
    for (BindingKey key : bindsInstances) {
      logger.n(TAG + ".collectFromBindsInstance: component" +
      component + " key " + key);
      DependencyInfo dependencyInfo = new DependencyInfo(
          DependencySourceType.BINDS_INTANCE,
          key,
          new HashSet<>(),
          component,
          null,
          UNIQUE,
          coreInjectorInfo);
      addDependencyInfo(result, dependencyInfo);
    }
  }

  private void printMultiMap(String message, SetMultimap<BindingKey, DependencyInfo> result) {
    logger.n(message);
    for (BindingKey key : result.keySet()) {
      logger.n(key.toString());
      for (DependencyInfo dependencyInfo : result.get(key)) {
        logger.n("%s", dependencyInfo);
      }
    }
    logger.n(message + " done.");

  }

  /**
   * Returns map from (sub)components to {@link DependencyInfo}s from their dependencies.
   */
  public SetMultimap<TypeElement, DependencyInfo> collectFromComponentDependencies(
      SetMultimap<TypeElement, TypeElement> componentToDependenciesMap,
      Map<TypeElement, CoreInjectorInfo> componentToCoreInjectorMap) {
    SetMultimap<TypeElement, DependencyInfo> result = HashMultimap.create();

    for (TypeElement c : componentToDependenciesMap.keySet()) {
      SetMultimap<BindingKey, DependencyInfo> keyToDependencyInfoMap = HashMultimap.create();
      for (TypeElement componentDependency : componentToDependenciesMap.get(c)) {
        collectFromComponentDependency(
            keyToDependencyInfoMap, componentDependency, componentToCoreInjectorMap.get(c));
      }
      for (BindingKey key : keyToDependencyInfoMap.keySet()) {
        DependencyInfo dependencyInfo = Iterables.getOnlyElement(keyToDependencyInfoMap.get(key));
        Preconditions.checkState(
            result.put(c, dependencyInfo),
            "Adding componendDependencies bindings failed: component "
                + c
                + " dependencyInfo "
                + dependencyInfo
                + " result "
                + result);
      }
    }
    return result;
  }

  private void collectFromComponentDependencies(SetMultimap<BindingKey, DependencyInfo> result,
      SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToComponentDependencyMap) {
    for (CoreInjectorInfo coreInjectorInfo : coreInjectorToComponentDependencyMap.keySet()) {
      for (TypeElement componentDependency :
          coreInjectorToComponentDependencyMap.get(coreInjectorInfo)) {
        collectFromComponentDependency(result, componentDependency, coreInjectorInfo);
      }
    }
  }

  private void collectFromComponentDependencies(SetMultimap<BindingKey, DependencyInfo> result,
      Set<TypeElement> componentDependencies) {
    for (TypeElement componentDependency : componentDependencies) {
        collectFromComponentDependency(result, componentDependency, null);
    }
  }

  private void collectFromComponentDependency(SetMultimap<BindingKey, DependencyInfo> result,
      TypeElement componentDependency, CoreInjectorInfo coreInjectorInfo) {
    // For v1, components is generated, not dependencies that from outside. All it can provides/injects is
    // from either module or real dependencies. Dagger's usage of dependencies for parent component
    // is kind of fragile.
    // For v2, it is just a dependency. TODO: make its provision/injection methods inherited.

    // if (utils.isComponent(componentDependency)) {
    //   return;
    // }

    // for itself
    DependencyInfo info = new DependencyInfo(
        DependencySourceType.COMPONENT_DEPENDENCIES_ITSELF,
        BindingKey.get(componentDependency),
        new HashSet<>(),
        componentDependency,
        null,
        UNIQUE,
        coreInjectorInfo);
    addDependencyInfo(result, info);

    collectFromComponentDependency(
        result, componentDependency, componentDependency, coreInjectorInfo);
  }

  public SetMultimap<BindingKey, DependencyInfo> collectFromComponentDependency(
      TypeElement componentDependency) {
    SetMultimap<BindingKey, DependencyInfo> result = HashMultimap.create();
    collectFromComponentDependency(result, componentDependency, componentDependency, null);
    return result;
  }

  private void collectFromComponentDependency(SetMultimap<BindingKey, DependencyInfo> result,
      TypeElement typeElement, TypeElement componentDependency, CoreInjectorInfo coreInjectorInfo) {

    // logger.n("componentDependency: %s, current element: %s", componentDependency, typeElement);
    // Avoid getting into Object finally.
    // TypeMirror superclass = componentDependency.getSuperclass();
    // if (!(superclass instanceof NoType)) {
    //   collectFromComponentDependency(result, (TypeElement) ((DeclaredType)
    // superclass).asElement());
    // }

    // for super
    for (TypeMirror typeMirror : typeElement.getInterfaces()) {
      collectFromComponentDependency(
          result,
          (TypeElement) ((DeclaredType) typeMirror).asElement(),
          componentDependency,
          coreInjectorInfo);
    }

    // for its methods
    for (Element e : typeElement.getEnclosedElements()) {
      if (utils.isComponentDependencyProvisionMethod(e)) {
        DependencyInfo dependencyInfo =
            getDependencyInfoForMethod(
                (ExecutableElement) e,
                DependencySourceType.COMPONENT_DEPENDENCIES_METHOD,
                componentDependency,
                coreInjectorInfo);
        if (dependencyInfo == null) {
          logger.n("Unbindable method: " + e);

          continue;
        }
        // logger.n(" dependencyInfo: " + dependencyInfo);
        // TODO: restore this
        // Preconditions.checkState(
            addDependencyInfo(result, dependencyInfo);
            // "adding dependency failed. dependencyInfo: " + dependencyInfo);
      }
    }
  }

  /**
   * Add dependencies from ctor injected classes needed by requiredKeys recursively to result. Key
   * for (sub_component builders depends on nothing even for @Subcomponent.Builders because they are
   * provided by the top level injectors directly. We add them here in requiredKeys and
   * key-to-dependencyInfo maps so that they are handled in a consistent way. Only difference will
   * be when generating code calling them. TODO: refactor this with {@link
   * Utils#getDependencyInfo(SetMultimap, BindingKey)} to handle Provider, Lazy and Optional
   * consistently.
   */
  private void completeDependenciesAndRequiredKeys(
      SetMultimap<BindingKey, DependencyInfo> result, Set<BindingKey> requiredKeys) {
    completeDependenciesAndRequiredKeys(result, requiredKeys, Sets.newHashSet());
  }

  private void completeDependenciesAndRequiredKeys(
        SetMultimap<BindingKey, DependencyInfo> result, Set<BindingKey> requiredKeys,
    Set<BindingKey> unresolved) {
    Collection<DependencyInfo> dependencies;
    DependencyInfo dependency;
    // Added all the required dependencies from ctor injected classes.
    while (!requiredKeys.isEmpty()) {
      BindingKey key = Iterables.getFirst(requiredKeys, null);
      Preconditions.checkNotNull(key);
      requiredKeys.remove(key);
      TypeName typeName = key.getTypeName();
      DeclaredType type = (DeclaredType) utils.getTypeFromTypeName(typeName);
      TypeElement classTypeElement = (TypeElement) type.asElement();
      Preconditions.checkNotNull(classTypeElement, String.format("Class %s not found.", type));
      // logger.n(TAG + ".addDependenciesForRequiredKeys: typeName " + typeName);
      if (result.containsKey(key)) {
        continue;
      } else if (key.getTypeName().isPrimitive()) {
        BindingKey boxed = BindingKey.get(key.getTypeName().box(), key.getQualifier());
        if (!result.containsKey(boxed)) {
          logger.n(TAG + ".addDependenciesForRequiredKeys: binding not found for key " + key);
        }
        continue;
      } else if (key.getTypeName().isBoxedPrimitive()) {
        BindingKey unboxed = BindingKey.get(key.getTypeName().unbox(), key.getQualifier());
        if (!result.containsKey(unboxed)) {
          logger.n(TAG + ".addDependenciesForRequiredKeys: binding not found for key " + key);
        }
        continue;
      } else if (utils.isOptional(key)) {
        if (utils.getDependencyInfo(result, key) == null) {
          logger.n(TAG + " key " + key + "'s dependencies not found");
        }
        continue;
      } else if (utils.isProviderOrLazy(typeName)) {
        key = utils.getElementKeyForParameterizedBinding(key);
        requiredKeys.add(key);
        continue;
      } else if (utils.isMapWithBuiltinValueType(key)) {
        BindingKey peeledMapKey = Preconditions.checkNotNull(utils.peelMapWithBuiltinValue(key));
        requiredKeys.add(peeledMapKey);
        continue;
      } else if (utils.isMap(key)) {
        if (getMapContributorKeys(result.keySet(), key).isEmpty()) {
          // Not found, must be from parent.
          unresolved.add(key);
          logger.n("Binding not found for : " + key);
        }
        continue;
      } else if (utils.isDaggerMembersInjector(typeName)) {
        BindingKey childKey = utils.getElementKeyForParameterizedBinding(key);
        Set<BindingKey> keys =
            collectRequiredKeysFromInjectedClass(utils.getTypeElement(childKey));
        DeclaredType childType =
            (DeclaredType) utils.getTypeFromTypeName(childKey.getTypeName());
        TypeElement childClassTypeElement = (TypeElement) childType.asElement();
        // child, to make generation code happy.
        // dependency =
        //     new DependencyInfo(
        //         DependencySourceType.DAGGER_MEMBERS_INJECTOR,
        //         childKey,
        //         Sets.newHashSet(keys),
        //         childClassTypeElement,
        //         ProvisionType.UNIQUE);
        // addDependencyInfo(result, dependency);
        // itself
        dependency =
            new DependencyInfo(
                DependencySourceType.DAGGER_MEMBERS_INJECTOR,
                key,
                Sets.newHashSet(keys),
                childClassTypeElement,
                ProvisionType.UNIQUE);

      } else

      // ClassName className;
      // if (typeName instanceof ClassName) {
      //   className = (ClassName) typeName;
      // } else {
      //   Preconditions.checkState(
      //       typeName instanceof ParameterizedTypeName,
      //       "Expect a %s but get %s",
      //       ParameterizedTypeName.class,
      //       typeName);
      //   ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
      //   for (TypeName parameter : parameterizedTypeName.typeArguments) {
      //     Preconditions.checkState(
      //         parameter instanceof ClassName || parameter instanceof ParameterizedTypeName,
      //         String.format("Unexpected parameter type %s for type %s.", parameter, typeName));
      //   }
      //   DependencyInfo dependencyInfo = utils.getDependencyInfoByGeneric(result, key);
      //   if (dependencyInfo != null) {
      //     requiredKeys.addAll(dependencyInfo.getDependencies());
      //     continue;
      //   } else {
      //     className = ((ParameterizedTypeName) typeName).rawType;
      //   }
      // }
      if (utils.isEitherComponentBuilder(classTypeElement)) {
        dependency =
            new DependencyInfo(
                DependencySourceType.EITHER_COMPONENT_BUILDER,
                key,
                new HashSet<>(),
                classTypeElement,
                ProvisionType.UNIQUE);
      } else if (utils.isEitherComponent(classTypeElement)) {
        dependency =
            new DependencyInfo(
                DependencySourceType.EITHER_COMPONENT,
                key,
                new HashSet<>(),
                classTypeElement,
                ProvisionType.UNIQUE);
      } else {
        dependencies = collectFromCtorInjectedClass(classTypeElement, type);
        if (dependencies == null) {
          unresolved.add(key);
          continue;
        }
        dependency = Iterables.getOnlyElement(dependencies);
      }
      // if (typeName instanceof ParameterizedTypeName) {
      //   Map<TypeVariableName, TypeName> parameterMap =
      //       utils.getMapFromTypeVariableToSpecialized(
      //           (ParameterizedTypeName) typeName,
      //           (ParameterizedTypeName) dependency.getDependant().getTypeName());
      //   requiredKeys.addAll(utils.specializeIfNeeded(dependency.getDependencies(),
      // parameterMap));
      // } else {
      // logger.n(
      //     TAG + ".addDependenciesForRequiredKeys new keys: " + dependency.getDependencies());
      requiredKeys.addAll(dependency.getDependencies());
      // }
      checkOneDependency(result, dependency);
      addDependencyInfo(result, dependency);
    }
  }

  /**
   * Returns the {@link BindingKey} from the given set that contributes into give key, which must be
   * map. Peeling is be handled before by {@link Utils#peelMapWithBuiltinValue(BindingKey)}. The
   * case that Map<Foo> contributes to Map<Foo> is handled the same way as the samplest case. Now we
   * need to handle the case that Map<SomeFooImpl> contributes to Map<? extends Foo>. TODO: Hanlde
   * other cases.
   */
  private Set<BindingKey> getMapContributorKeys(Set<BindingKey> bindingKeys, BindingKey key) {
    logger.n(TAG + ".getMapContributorKeys key: " + key);
    Preconditions.checkArgument(utils.isMap(key), "Need a map but got " + key);
    Set<BindingKey> result = new HashSet<>();
    AnnotationSpec qualifier = key.getQualifier();
    // TypeName type =
    //     Iterables.getOnlyElement(((ParameterizedTypeName) key.getTypeName()).typeArguments);
    // type = utils.getCoreType(type);

    ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) key.getTypeName();
    TypeName typeName = parameterizedTypeName.typeArguments.get(0);
    Preconditions.checkState(
        !utils.isProviderOrLazy(typeName),
        "builtin bind should have been handled in upper stream. But still got " + key);
    if (!(typeName instanceof WildcardTypeName)) {
      return  result;
    }
    WildcardTypeName wildcardTypeName = (WildcardTypeName) typeName;
    if (!wildcardTypeName.lowerBounds.isEmpty()) {
      logger.w("lowerBounds not supported yet. Key: " + key);
      return result;
    }
    Iterable<TypeMirror> upperBoundTypes =
        Iterables.transform(
            wildcardTypeName.upperBounds,
            new Function<TypeName, TypeMirror>() {
              @Override
              public TypeMirror apply(TypeName input) {
                ClassName className = (ClassName) input;
                return  elements.getTypeElement(utils.getCanonicalName(className)).asType();
              }
            });
    for (BindingKey k : bindingKeys) {
      logger.n(TAG + ".getMapContributorKeys k: " + k);

      if (!utils.isMap(k)) {
        continue;
      }
      logger.n(TAG + ".getMapContributorKeys k: 1");

      ParameterizedTypeName loopParameterizedTypeName = (ParameterizedTypeName) k.getTypeName();
      TypeName loopTypeName = loopParameterizedTypeName.typeArguments.get(0);
      if (loopTypeName instanceof WildcardTypeName) {
        return  result;
      }
      logger.n(TAG + ".getMapContributorKeys k: 2");

      ClassName className = (ClassName) loopTypeName;
      TypeMirror typeMirror = elements.getTypeElement(utils.getCanonicalName(className)).asType();
      logger.n(TAG + ".getMapContributorKeys k type: " + typeMirror);

      boolean isSubtype = true;
      for (TypeMirror i : upperBoundTypes) {
        if (!types.isSubtype(typeMirror, i)) {
          logger.n(TAG + ".getMapContributorKeys k: super type  " + i);

          isSubtype = false;
          break;
        }
      }
      if (isSubtype) {
        result.add(k);
      }
    }
    return result;
  }

  /**
   * Returns all the {@link BindingKey} required by give dependencyInfos and injectors
   * recursively.
   */
  public Set<BindingKey> getRequiredKeys(
      Collection<TypeElement> components, Collection<DependencyInfo> dependencyInfos) {
    Set<BindingKey> result =
        getRequiredKeys(components, collectionToMultimap(dependencyInfos));

    return result;
  }

  /**
   * Returns {@link BindingKey}s required by the give injected classes and dependency
   * infos both dependencies and dependant.
   */
  private Set<BindingKey> getRequiredKeys(
      Collection<TypeElement> components,
      SetMultimap<BindingKey, DependencyInfo> dependencyInfos) {
    Set<BindingKey> requiredKeys = new HashSet<>();
    for (TypeElement c : components) {
      Set<BindingKey> keysFromComponent = getRequiredKeys(c);
      requiredKeys.addAll(keysFromComponent);
      logger.n(TAG + ".getRequiredKeys from component " + c + keysFromComponent);
    }

    for (BindingKey key: dependencyInfos.keySet()) {
      if (utils.isBindable(key.getTypeName())) {
        requiredKeys.add(key);
      }
    }

    for (DependencyInfo info : dependencyInfos.values()) {
      for (BindingKey key : info.getDependencies()) {
        if (utils.isBindable(key.getTypeName())) {
          requiredKeys.add(key);
        }
      }
    }

    return requiredKeys;
  }

  /**
   * Returns all the required {@link BindingKey}s by the provision methods and injection methods
   * included in the given class directly.
   */
  private Set<BindingKey> getRequiredKeys(TypeElement component) {
    Set<BindingKey> result = new HashSet<>();
    TypeMirror superType = component.getSuperclass();
    logger.n(TAG + ".getRequiredKeys:  component: " + component + " superType " + superType);

    utils.traverseAndDo(
        types,
        (DeclaredType) component.asType(),
        component,
        x -> {
          TypeMirror type = x.getFirst();
          Element element = x.getSecond();
          logger.n(TAG + ".getRequiredKeys: element: " + element + " type: " + type);

          if (!element.getKind().equals(ElementKind.METHOD)) {
            return null;
          }
          if (!element.getModifiers().contains(Modifier.ABSTRACT)) {
            return null;
          }
          ExecutableElement method = (ExecutableElement) element;
          logger.n(TAG + ".getRequiredKeys: method: " + method);
          ExecutableType methodType = (ExecutableType) type;
          AnnotationMirror qualifier = utils.getQualifier(method);
          if (utils.isProvisionMethodInInjector(method)) {
            result.add(BindingKey.get(methodType.getReturnType(), qualifier));
          }
          if (utils.isInjectionMethod(method)) {
            DeclaredType injectedType =
                ((DeclaredType) Iterables.getOnlyElement(methodType.getParameterTypes()));
            TypeElement injectedElement = (TypeElement) injectedType.asElement();
            result.addAll(collectRequiredKeysFromInjectedClass(injectedElement));
          }
          return null;
        });

    return result;
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
   * {@link DependencyInfo#getDependant()} is for a generic class.
   */
  public static SetMultimap<BindingKey, DependencyInfo> collectionToMultimap(
      Collection<DependencyInfo> dependencies) {
    SetMultimap<BindingKey, DependencyInfo> result = HashMultimap.create();
    for (DependencyInfo info : dependencies) {
      BindingKey key = info.getDependant();
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
          key = BindingKey.get(typeName, key.getQualifier());
        }
      }

      result.put(key, info);
    }
    return result;
  }

  private void addDependencyInfo(
      SetMultimap<BindingKey, DependencyInfo> existingDependencies,
      Collection<DependencyInfo> newDependencies) {
    for (DependencyInfo info : newDependencies) {
      if (!addDependencyInfo(existingDependencies, info)) {
       // TODO restore this;
        // errors.add(String.format("Adding dependency failed for %s.", info));
      }
    }
  }

  /**
   * Adds the give {@link DependencyInfo} to the map, handling generic type with formal
   * parameters. Returns if it changed the given map.
   */
  private boolean addDependencyInfo(
      SetMultimap<BindingKey, DependencyInfo> existingDependencies, DependencyInfo info) {
    checkOneDependency(existingDependencies, info);

    BindingKey key = info.getDependant();
    TypeName typeName = key.getTypeName();
    // For generic type with type variable, only keep raw type.
    if (typeName instanceof ParameterizedTypeName) {
      ParameterizedTypeName parameterizedTypeName = (ParameterizedTypeName) typeName;
      for (TypeName anyParameter : parameterizedTypeName.typeArguments) {
        if (anyParameter instanceof TypeVariableName) {
          logger.e("unexpected type with TypeVariable: " + typeName);
          // typeName = parameterizedTypeName.rawType;
          // key = BindingKey.get(typeName, key.getQualifier());
        }
      }
    }

    /**
     * TODO: remove this. This hack elimite duplicate UNIQUE deps. It depends on the order deps are
     * collected, module->componentDependencies->bindsInstance. Check the warning from {@link
     * #checkOneDependency(SetMultimap, DependencyInfo)}.
     */
    if (info.getType().equals(UNIQUE) && existingDependencies.containsKey(key)) {
      DependencyInfo old = Iterables.getOnlyElement(existingDependencies.get(key));
      if (shouldKeep(info, old)) {
        logger.w(
            TAG
                + ".addDependencyInfo: old info is stronger then the new one. Old: "
                + old
                + " new: "
                + info);
        return false;
      } else {
        existingDependencies.get(key).clear();
      }
    }
    boolean result = existingDependencies.put(key, info);
    Preconditions.checkState(result, "failed to add dependency: " + info);
    return true;
  }

  private boolean shouldKeep(DependencyInfo info, DependencyInfo old) {
    if (info.getDependant().getTypeName().toString().contains("AppCompatActivity")) {
      return !info.getSourceClassElement()
          .getQualifiedName()
          .toString()
          .contains("GmmActivityComponent");
    }
    if (info.getDependant().getTypeName().toString().contains("UiTransitionStateApplier")) {
      boolean result = info.getSourceClassElement()
          .getQualifiedName()
          .toString()
          .contains("NoOpModule");
      logger.n("UiTransitionStateApplier from " + info.getSourceClassElement() + " shouldKeep " + result);
      return result;
    }
    return info.getDependencySourceType().getPriority()
        <= old.getDependencySourceType().getPriority();
  }

  /**
   * TODO: current gmm DI way has duplicate unique binding, e.g., from ctor injected class and
   * component dependencies. fix it.
   */
  private void checkOneDependency(
      SetMultimap<BindingKey, DependencyInfo> existingDependencies, DependencyInfo newInfo) {
    Preconditions.checkNotNull(newInfo);
    if (existingDependencies.containsKey(newInfo.getDependant())) {
      Set<DependencyInfo> dependencyInfoSet = existingDependencies.get(newInfo.getDependant());
      DependencyInfo existingDependencyInfo =
          Preconditions.checkNotNull(Iterables.getFirst(dependencyInfoSet, null));
      if ((existingDependencyInfo.getType().equals(UNIQUE)
          || newInfo.getType().equals(UNIQUE)) && !existingDependencyInfo.equals(newInfo)) {

        //TODO: fix this
        logger.w("Duplicate dependency. old: " + dependencyInfoSet
        + " new: " + newInfo);
        // String error =
        //     String.format(
        //         "Adding dependencies failed.\n %s\nAlready existing: %s", newInfo, dependencyInfoSet);
        // errors.add(error);
      }
    }
  }

  /**
   * Collects dependencies from a given {@link dagger.Module}. Type.SET and
   * Type.SET_VALUES are put together with Key.get(Set<elementType>, annotation)
   * for easier later processing.
   */
  public Collection<DependencyInfo>  collectFromModule(TypeElement module) {
    logger.n(TAG + ".collectFromModule: module " + module);
    Collection<DependencyInfo> result = new HashSet<>();
    for (Element e : module.getEnclosedElements()) {
      logger.n("element: %s", e);
      if (!utils.isProvisionMethodInModule(e)) {
        continue;
      }
      logger.n("provision method element: %s", e);

      ExecutableElement executableElement = (ExecutableElement) e;
      DependencyInfo dependencyInfo = getDependencyInfoForMethod(executableElement,
          DependencySourceType.MODULE, null);
      if (dependencyInfo == null) {
        logger.w("Unbindable method: " + e);

        continue;
      }

      result.add(dependencyInfo);
    }

//    logger.n(String.format("collectFromModule: result: %s", result));
    return result;
  }

  /**
   * Return {@link DependencyInfo} for the given method, null if failed.
   */
  private @Nullable DependencyInfo getDependencyInfoForMethod(ExecutableElement method,
      DependencySourceType dependencySourceType, @Nullable CoreInjectorInfo coreInjectorInfo) {
    return getDependencyInfoForMethod(method, dependencySourceType, null, coreInjectorInfo);
  }

  public @Nullable DependencyInfo getDependencyInfoForMethod(
      ExecutableElement method,
      DependencySourceType dependencySourceType,
      @Nullable TypeElement source,
      @Nullable CoreInjectorInfo coreInjectorInfo) {
    BindingKey key = getBindingKeyForMethod(method);
    if (key == null) {
      return null;
    }

    // logger.n(TAG + ".getDependencyInfoForMethod: method " + method);
    AnnotationMirror annotation = utils.getQualifier(method);
    List<BindingKey> keys = utils.getDependenciesFromExecutableElement(method);

    // Could be from module or other source like component dependencies.
    ProvisionType provideType =
        utils.isProvisionMethodInModule(method) ? utils.getProvisionType(method) : UNIQUE;

    if (SET.equals(provideType)) {
      key =
          BindingKey.get(
              ParameterizedTypeName.get(ClassName.get(Set.class), key.getTypeName()), annotation);
    } else if (MAP.equals(provideType) && !utils.isMultibindsMethod(method)) {
      AnnotationMirror mapKeyedMirror =
          Preconditions.checkNotNull(
              utils.getMapKey(method), String.format("Map binding %s missed MapKey.", method));
      AnnotationMirror mapKeyMirror =
          utils.getAnnotationMirror(mapKeyedMirror.getAnnotationType().asElement(), MapKey.class);
      AnnotationValue unwrapValue = utils.getAnnotationValue(elements, mapKeyMirror, "unwrapValue");
      if (unwrapValue != null && !((Boolean) unwrapValue.getValue())) {
        logger.e(
            String.format(
                "MapKey with unwrapValue false is not supported, yet. Biding: %s", method));
      }
      TypeMirror keyTypeMirror =
          Preconditions.checkNotNull(
              utils.getElementTypeMirror(mapKeyedMirror, "value"),
              String.format("Get key type failed for binding %s", method));
      if (keyTypeMirror instanceof PrimitiveType) {
        keyTypeMirror = types.boxedClass((PrimitiveType) keyTypeMirror).asType();
      }
      TypeMirror valueTypeMirror = method.getReturnType();
      AnnotationMirror qualifier = utils.getQualifier(method);
      key =
          BindingKey.get(
              ParameterizedTypeName.get(
                  ClassName.get(Map.class),
                  TypeName.get(keyTypeMirror),
                  TypeName.get(valueTypeMirror)),
              qualifier);
    }
    DependencyInfo result = new DependencyInfo(
        dependencySourceType,
        key,
        Sets.newHashSet(keys),
        source != null ? source : (TypeElement) method.getEnclosingElement(),
        method,
        provideType,
        coreInjectorInfo
    );
    // logger.n(TAG + ".getDependencyInfoForMethod: result " + " " + result);

    return result;
  }

  /**
   * Return binding key for given method, null if n/a.
   */
  private BindingKey getBindingKeyForMethod(ExecutableElement executableElement) {
    TypeMirror returnType = executableElement.getReturnType();
    if (!utils.isBindableType(returnType)) {
      return null;
    }

    AnnotationMirror annotation = utils.getQualifier(executableElement);
    if (utils.isBindsOptionalOfMethod(executableElement)) {
      return BindingKey.get(
          ParameterizedTypeName.get(ClassName.get(Optional.class), TypeName.get(returnType)),
          annotation);
    } else {
      return BindingKey.get(returnType, annotation);
    }
  }

  /**
   * Returns dependency info from ctor injected class, null if such class not found.
   */
  @Nullable
  public Collection<DependencyInfo> collectFromCtorInjectedClass(
      TypeElement classElement, DeclaredType type) {
    // logger.n(TAG + ".collectFromCtorInjectedClass: classElement: " + classElement);
    Collection<DependencyInfo> result = new HashSet<>();
    Preconditions.checkArgument(
        !utils.hasAnonymousParentClass(classElement),
        String.format("class %s should not have anonymous ancestor.", classElement));

    ExecutableElement ctor = utils.findInjectedCtor(classElement);

    // logger.n(TAG + ".collectFromCtorInjectedClass ctor: " + ctor);
    if (ctor == null) {
      return null;
    }

    TypeMirror typeMirror = types.asMemberOf(type, ctor);
    // logger.n(TAG + ".collectFromCtorInjectedClass typeMirror: " + typeMirror);
    Set<BindingKey> dependencies = getDependenciesFrom(ctor, (ExecutableType) typeMirror);

    for (Element element : classElement.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.FIELD) && utils.isInjected(element)) {
        dependencies.add(utils.getKeyForField(type, element));
      }
      if (element.getKind().equals(ElementKind.METHOD) && utils.isInjected(element)) {
        dependencies.addAll(
            getDependenciesFrom((ExecutableElement) element,
                (ExecutableType) types.asMemberOf(type, element)));
      }
    }

    DependencyInfo dependenceInfo =
        new DependencyInfo(
            DependencySourceType.CTOR_INJECTED_CLASS,
            BindingKey.get(type),
            dependencies,
            classElement,
            UNIQUE);
    result.add(dependenceInfo);

    return result;
  }


  /**
   * Returns {@link DependencyInfo} from the give class whose is created outside of DI and needs
   * to be injected.
   */
  private Set<BindingKey> collectRequiredKeysFromInjectedClass(TypeElement classElement) {
    logger.n("collectFromInjectedClass, classElement: " + classElement);
    Preconditions.checkArgument(
        !utils.hasAnonymousParentClass(classElement),
        String.format("class %s should not be or have anonymous ancestor class.", classElement));

    DeclaredType declaredType = (DeclaredType) classElement.asType();
    Set<BindingKey> result = collectFromInjectedMembersRecursively(classElement, declaredType);
    logger.n("collectFromInjectedClass, result: " + result);
    return result;
  }

  /**
   * Collects dependencies of the give {@link TypeElement} from the injected
   * members, either fields or methods, of the {@link TypeElement}.
   */
  private Set<BindingKey> collectFromInjectedMembersRecursively(TypeElement classElement,
      DeclaredType declaredType) {
    if (utils.findInjectedCtor(classElement) != null) {
        logger.w(String.format("class %s is injected and should not have injected ctor", classElement));
    }

    Set<BindingKey> dependencies = new HashSet<>();

    TypeMirror superMirror = classElement.getSuperclass();
    if (!superMirror.getKind().equals(TypeKind.NONE)) {
      TypeElement parent = (TypeElement) ((DeclaredType) superMirror).asElement();
      Set<BindingKey> keys = collectFromInjectedMembersRecursively(parent, declaredType);
      dependencies.addAll(keys);
    }

    for (TypeMirror typeMirror : classElement.getInterfaces()) {
      TypeElement typeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();
      Set<BindingKey> keys = collectFromInjectedMembersRecursively(typeElement, declaredType);

      dependencies.addAll(keys);
    }

    for (Element element : classElement.getEnclosedElements()) {
   // for (Element element : elements.getAllMembers(classElement)) {
      TypeMirror typeMirror = types.asMemberOf((DeclaredType) classElement.asType(), element);
      // messager.printMessage(
      //     Kind.NOTE,
      //     TAG
      //         + ".collectFromInjectedMembersRecursively:"
      //         + " element: "
      //         + element
      //         + " kind: "
      //         + element.getKind()
      //         + " type: "
      //         + typeMirror);
      if (element.getKind().equals(ElementKind.FIELD) && utils.isInjected(element)) {
        // logger.n(
        //     TAG
        //         + ".collectFromInjectedMembersRecursively:"
        //         + " found element: "
        //         + element
        //         + " kind: "
        //         + element.getKind()
        //         + " type: "
        //         + typeMirror);

        dependencies.add(BindingKey.get(typeMirror, utils.getQualifier(element)));
      }
      if (element.getKind().equals(ElementKind.METHOD) && utils.isInjected(element)) {
        // logger.n(
        //     TAG
        //         + ".collectFromInjectedMembersRecursively:"
        //         + " found element: "
        //         + element
        //         + " kind: "
        //         + element.getKind()
        //         + " type: "
        //         + typeMirror);
        dependencies.addAll(getDependenciesFrom((ExecutableElement) element,
            (ExecutableType) typeMirror));
      }
    }

    if (!dependencies.isEmpty() && !classElement.getModifiers().contains(Modifier.PUBLIC)) {
      logger.w("non-public injected class? %s", classElement);
    }
    return dependencies;
  }

  private Set<BindingKey> getDependenciesFrom(ExecutableElement element,
      ExecutableType executableType) {
    Set<BindingKey> result = new HashSet<>();
    List<? extends TypeMirror> parameterTypes = executableType.getParameterTypes();
    List<? extends VariableElement> parameters = element.getParameters();
    Preconditions.checkState(parameterTypes.size() == parameters.size());

    for (int i = 0; i < parameters.size(); i ++) {
      result.add(BindingKey.get(parameterTypes.get(i), utils.getQualifier(parameters.get(i))));
    }
    return result;
  }
}
