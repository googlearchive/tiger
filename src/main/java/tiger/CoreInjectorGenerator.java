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

import static tiger.ProvisionType.SET;
import static tiger.ProvisionType.SET_VALUES;

import com.google.common.base.Pair;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import dagger.Lazy;
import dagger.MapKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import com.google.common.base.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Generated;
import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
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
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * Generates packaged injectors, multi-binding injectors and top level injectors. The former is to
 * get around accessibility limitation. The second is unscoped injector dedicated for
 * multi-bindings. There is only one class for each scope, though could/will be multiple instances.
 * Top level injectors orchestrate everything.
 *
 * <p>PackagedInjector for a {@link BindingKey} is decided this way.
 *
 * <ul>
 *   <li>Provided Set(potentially all multi) binding: unscoped dedicated global multi-binding
 *       injector. All multi-bindings are unscoped. If scoped, ignored(or disallowed at all). For
 *       each binding of a multi-binding, they will have a non-null dependencyInfo from which the
 *       packaged injector can be deduced. Contributors of multi-bindings providing non-set binding
 *       and handled normally as below.
 *   <li>Built-in binding, i.e., some_qualifier Provider<Foo> or Lazy<Foo>: binding provider package
 *       of some_qualifier Foo.
 *   <li>Provided non-set binding: binding provider package.
 *   <li>Other(MUST be resolved by generic binding): go to referencing package. if Referencing class
 *       is generic, then continue. This way we could run into to access problem if the referenced
 *       generic class is not public. But better chance is that the type variable is not public but
 *       accessible by the original non-generic class. Let's see how it works. This therefore could
 *       be created in multiple PackagedInjectors. It MUST be unscoped.
 * </ul>
 *
 * Generated injectors are not thread safe for performance reason. Callers must guarantee thread
 * safe if needed.
 *
 * <p>All types are provided by packaged injectors and referred by other packaged injectors and
 * top-level injectors. The ONLY exception is Subcomponents. In packaged injectors the subcomponent
 * in context is returned. But the toplevel provision method will contruct the component by its
 * ctor.
 *
 * <p>TODO: revisit all the asMemberOf to make sure only direct container is used.
 */
class CoreInjectorGenerator {
  private static String TAG = "CoreInjectorGenerator";

  /**
   * Used for value of @Generated(). It starts with "dagger." so that it will be exempted from
   * strict java deps check.
   */
  private static final String GENERATOR_NAME = "dagger.CoreInjectorGenerator";

  private static final boolean LOG_PROVISION_METHOD_ENABLED = false;
  private static String LOCK_HOLDER_PACKAGE_STRING = "lock.holder";
  private static String LOCK_HOLDER_CLASS_STRING = "LockHolder";
  private static String LOCK_HOLDER_FIELD_STRIN = "theLock";

  static final String PACKAGED_INJECTOR_NAME = "PackagedInjector";
  static final String MULTI_BINDING_INJECTOR_NAME = "MultiBindingInjector";
  private static final String TOP_LEVEL_INJECTOR_FIELD = "topLevelInjector";
  // Refers the packaged injector for parent scope.
  private static final String CONTAINING_PACKAGED_INJECTOR_FIELD = "containingPackagedInjector";
  private static final String UNSCOPED_SUFFIX = "_unscoped";

  private final SetMultimap<BindingKey, DependencyInfo> dependencies;
  private final Set<BindingKey> explicitScopes;
  private final ScopeCalculator scopeCalculator;
  private final ScopeAliasCondenser scopeAliasCondenser;
  private final SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToComponentDependencyMap;
  private final SetMultimap<CoreInjectorInfo, BindingKey> coreInjectorToBindsInstanceMap;
  private final Utils utils;
  private SetMultimap<CoreInjectorInfo, TypeElement> nonNullaryCtorModules = HashMultimap.create();
  private Set<TypeElement> nonNullaryCtorUnscopedModules = new HashSet<>();
  private final SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToComponentMap;
  // Mapping from child to parent.
  private final Map<CoreInjectorInfo, CoreInjectorInfo> componentTree;
  private final List<CoreInjectorInfo> orderedCoreinjectors;
  private final String topLevelPackageString;
  private final List<String> errors = new ArrayList<>();

  // Includes multi-binding package.
  private final SetMultimap<ClassName, BindingKey> generatedBindingsForPackagedInjector =
      HashMultimap.create();
  // Includes dagger modules with getFooModule() generated for the packaged
  // injectors, i.e., <PackagedInjector, <Module, MethodSpec>>.
  private Map<ClassName, Map<ClassName, MethodSpec>> modulesWithGetter = Maps.newHashMap();

  // From packaged injector to spec builder.
  private final Map<ClassName, TypeSpec.Builder> packagedInjectorBuilders = Maps.newHashMap();

  // From packaged injector to injected ClassName.
  private final SetMultimap<ClassName, ClassName> injectedClassNamesForPackagedInjector =
      HashMultimap.create();

  private final SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToBothBuilderMap;

  private final ProcessingEnvironment processingEnv;
  private final Messager messager;
  private final Elements elements;
  private final Types types;

  private final String topLevelInjectorPrefix;
  private final String topLevelInjectorSuffix;

  public CoreInjectorGenerator(
      SetMultimap<BindingKey, DependencyInfo> dependencies,
      ScopeCalculator scopeCalculator,
      ScopeAliasCondenser scopeAliasCondenser,
      SetMultimap<CoreInjectorInfo, TypeElement> modules,
      Set<TypeElement> unscopedModules,
      SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToComponentMap,
      SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToBothBuilderMap,
      Map<CoreInjectorInfo, CoreInjectorInfo> componentTree,
      CoreInjectorInfo rootInjectorInfo,
      SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToComponentDependencyMap,
      SetMultimap<CoreInjectorInfo, BindingKey> coreInjectorToBindsInstanceMap,
      String topPackageString,
      String topLevelInjectorPrefix,
      String topLevelInjectorSuffix,
      ProcessingEnvironment env, Utils utils) {
    this.dependencies = dependencies;
    // Utilities.printDependencies(dependencies);
    this.scopeCalculator = scopeCalculator;
    this.scopeAliasCondenser = scopeAliasCondenser;
    this.explicitScopes = scopeCalculator.getExplicitScopedKeys();
    this.coreInjectorToComponentMap = LinkedHashMultimap.create(coreInjectorToComponentMap);
    this.componentTree = componentTree;
    this.coreInjectorToBothBuilderMap = coreInjectorToBothBuilderMap;
    this.topLevelPackageString = topPackageString;
    this.topLevelInjectorPrefix = topLevelInjectorPrefix;
    this.topLevelInjectorSuffix = topLevelInjectorSuffix;
    this.processingEnv = env;
    this.messager = env.getMessager();
    this.elements = env.getElementUtils();
    this.types = env.getTypeUtils();
    this.utils = utils;
    if (componentTree.isEmpty()) {
      this.orderedCoreinjectors = Lists.newArrayList();
      this.orderedCoreinjectors.add(rootInjectorInfo);
    } else {
      this.orderedCoreinjectors = this.utils.getOrderedScopes(componentTree);
    }
    this.coreInjectorToComponentDependencyMap = coreInjectorToComponentDependencyMap;
    this.coreInjectorToBindsInstanceMap = coreInjectorToBindsInstanceMap;
    for (CoreInjectorInfo coreInjectorInfo : orderedCoreinjectors) {
      nonNullaryCtorModules.putAll(
          coreInjectorInfo, this.utils.getNonNullaryCtorOnes(modules.get(coreInjectorInfo)));
    }
    nonNullaryCtorUnscopedModules.addAll(this.utils.getNonNullaryCtorOnes(unscopedModules));
  }

  /** Generates PackagedInjectors and return the generated. */
  public void generate() {
    messager.printMessage(
        Kind.NOTE, String.format("%s.generate() for %s", TAG, topLevelPackageString));

    generateLockHolder();

    generatePackagedInjectors();
    generateTopLevelInjectors();

    if (!errors.isEmpty()) {
      messager.printMessage(Kind.ERROR, "Generating injectors failed: ");
      for (String s : errors) {
        messager.printMessage(Kind.ERROR, s);
      }
    }
  }

  private void generateLockHolder() {
    TypeSpec.Builder builder =
        TypeSpec.classBuilder(LOCK_HOLDER_CLASS_STRING)
            .addModifiers(Modifier.PUBLIC)
            .addField(
                FieldSpec.builder(
                        ClassName.get(Lock.class),
                        LOCK_HOLDER_FIELD_STRIN,
                        Modifier.PUBLIC,
                        Modifier.STATIC)
                    .initializer("new $T()", ClassName.get(ReentrantLock.class))
                    .build());
    JavaFile javaFile = JavaFile.builder(LOCK_HOLDER_PACKAGE_STRING, builder.build()).build();

    try {
      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      Throwables.propagate(e);
    }
  }

  /** Get {@link TypeSpec} for packaged injector specified by className. */
  private TypeSpec.Builder getInjectorTypeSpecBuilder(ClassName injectorClassName) {
    if (!packagedInjectorBuilders.containsKey(injectorClassName)) {
      // Generate packaged injectors for all components. Containing is
      // needed so that the injector chain will not break.
      // Contained is needed to provide access of its containing packaged
      // injector for peer packaged injectors.
      for (CoreInjectorInfo component : orderedCoreinjectors) {
        TypeSpec.Builder typeSpecBuilder = createInjectorTypeSpec(component, injectorClassName);
        // typeSpecBuilder.addField(FieldSpec.builder(ClassName.get(GoogleLogger.class), "logger")
        // .initializer("$T.forEnclosingClass()", ClassName.get(GoogleLogger.class)).build());
        packagedInjectorBuilders.put(
            getInjectorNameOfScope(injectorClassName, component.getScope()), typeSpecBuilder);
      }
    }
    return packagedInjectorBuilders.get(injectorClassName);
  }

  private CoreInjectorInfo getComponentFromPackagedInjectorClassName(
      ClassName packagedInjectorClassName) {
    for (CoreInjectorInfo component : orderedCoreinjectors) {
      if (packagedInjectorClassName
          .simpleName()
          .contains(component.getScope().getQualifiedName().toString().replace(".", "_"))) {
        return component;
      }
    }
    throw new RuntimeException(
        String.format("Cannot found component for %s.", packagedInjectorClassName));
  }

  private static String getPackageFromInjectorClassName(ClassName injectorClassName) {
    return injectorClassName.packageName();
  }

  private TypeSpec.Builder createInjectorTypeSpec(
      CoreInjectorInfo coreInjectorInfo, ClassName injectorClassName) {
    ClassName cn = getInjectorNameOfScope(injectorClassName, coreInjectorInfo.getScope());
    // messager.printMessage(Kind.NOTE, "createPackagedInjectorTypeSpec. name: " +
    // Utilities.getClassCanonicalName(cn));
    TypeSpec.Builder typeSpecBuilder =
        TypeSpec.classBuilder(cn.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(
                AnnotationSpec.builder(Generated.class)
                    .addMember("value", "$S", GENERATOR_NAME)
                    .build());

    // Top level injector.
    ClassName topLevelInjectorClassName =
        ClassName.get(
            topLevelPackageString,
            getTopLevelInjectorName(
                coreInjectorInfo, topLevelInjectorPrefix, topLevelInjectorSuffix));
    typeSpecBuilder.addField(topLevelInjectorClassName, TOP_LEVEL_INJECTOR_FIELD, Modifier.PRIVATE);

    MethodSpec.Builder ctorSpec =
        MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(topLevelInjectorClassName, TOP_LEVEL_INJECTOR_FIELD);
    ctorSpec.addStatement("this.$N = $N", TOP_LEVEL_INJECTOR_FIELD, TOP_LEVEL_INJECTOR_FIELD);

    // Containing packaged injector.
    if (componentTree.get(coreInjectorInfo) != null) {
      ClassName containingInjectorClassName =
          getInjectorNameOfScope(injectorClassName, componentTree.get(coreInjectorInfo).getScope());
      typeSpecBuilder.addField(
          containingInjectorClassName, CONTAINING_PACKAGED_INJECTOR_FIELD, Modifier.PRIVATE);
      ctorSpec.addParameter(containingInjectorClassName, CONTAINING_PACKAGED_INJECTOR_FIELD);
      ctorSpec.addStatement(
          "this.$N = $N", CONTAINING_PACKAGED_INJECTOR_FIELD, CONTAINING_PACKAGED_INJECTOR_FIELD);
    }

    typeSpecBuilder.addMethod(ctorSpec.build());

    return typeSpecBuilder;
  }

  private void generatePackagedInjectors() {
    for (CoreInjectorInfo coreInjectorInfo : orderedCoreinjectors) {
      Set<ClassName> injected = new HashSet<>();
      Set<TypeElement> components =
          Sets.newHashSet(coreInjectorToComponentMap.get(coreInjectorInfo));
      for (TypeElement c : components) {
        for (Element e : elements.getAllMembers(c)) {
          messager.printMessage(Kind.NOTE, "generatePackagedInjectors: element: " + e);
          if (!utils.isMethod(e)) {
            continue;
          }
          ExecutableElement method = (ExecutableElement) e;
          ExecutableType methodType =
              ((ExecutableType) types.asMemberOf(((DeclaredType) c.asType()), e));
          if (utils.isInjectionMethod(method)) {

            TypeElement injectedTypeElement =
                (TypeElement)
                    ((DeclaredType) Iterables.getOnlyElement(methodType.getParameterTypes()))
                        .asElement();
            if (!injected.add(ClassName.get(injectedTypeElement))) {
              continue;
            }
            messager.printMessage(
                Kind.NOTE,
                TAG + ".generatePackagedInjectors: injection method for: " + injectedTypeElement);
            generateInjectionMethod(injectedTypeElement, coreInjectorInfo.getScope());
          } else if (utils.isProvisionMethodInInjector(method)) {
            generateProvisionMethodIfNeeded(
                utils.getKeyProvidedByMethod((ExecutableElement) method), c);
          } else {
            // TODO: ignore known elements like builders.
            messager.printMessage(
                Kind.WARNING, String.format("Unknown element %s from injector %s.", method, c));
          }
        }
      }
    }

    messager.printMessage(
        Kind.NOTE,
        TAG + ".generatePackagedInjectors. packagedInjectorBuilders: " + packagedInjectorBuilders);

    // Inherited provision methods.
    for (CoreInjectorInfo component : orderedCoreinjectors) {
      if (componentTree.get(component) == null) {
        continue;
      }
      for (Map.Entry<ClassName, TypeSpec.Builder> entry : packagedInjectorBuilders.entrySet()) {
        ClassName packagedInjectorClassName = entry.getKey();
        if (!component.equals(
            getComponentFromPackagedInjectorClassName(packagedInjectorClassName))) {
          continue;
        }
        generateInheritedProvisionMethods(packagedInjectorClassName);
      }
    }

    // Inherited injection methods.
    for (CoreInjectorInfo component : orderedCoreinjectors) {
      if (componentTree.get(component) == null) {
        continue;
      }
      for (Map.Entry<ClassName, TypeSpec.Builder> entry : packagedInjectorBuilders.entrySet()) {
        ClassName packagedInjectorClassName = entry.getKey();
        if (!component.equals(
            getComponentFromPackagedInjectorClassName(packagedInjectorClassName))) {
          continue;
        }
        generateInheritedInjectionMethods(packagedInjectorClassName);
      }
    }

    for (Map.Entry<ClassName, TypeSpec.Builder> entry : packagedInjectorBuilders.entrySet()) {
      String packageString = entry.getKey().packageName();
      TypeSpec.Builder builder = entry.getValue();
      JavaFile javaFile = JavaFile.builder(packageString, builder.build()).build();

      // messager.printMessage(
      //     Kind.NOTE, "javaFile for: " + builder.build() + "\n" + javaFile.toString());

      //       messager.printMessage(Kind.NOTE, String.format("java file: %s", javaFile));
      try {
        javaFile.writeTo(processingEnv.getFiler());
      } catch (IOException e) {
        Throwables.propagate(e);
      }
    }
  }

  private void generateInheritedProvisionMethods(ClassName packagedInjectorClassName) {
    CoreInjectorInfo component =
        getComponentFromPackagedInjectorClassName(packagedInjectorClassName);
    Preconditions.checkArgument(
        componentTree.get(component) != null,
        String.format(
            "No inherited provision methods to generate for %s", packagedInjectorClassName));

    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    ClassName containingPackagedInjectorClassName =
        getInjectorNameOfScope(packagedInjectorClassName, componentTree.get(component).getScope());
    for (BindingKey key :
        generatedBindingsForPackagedInjector.get(containingPackagedInjectorClassName)) {
      String provisionMethodName = utils.getProvisionMethodName(dependencies, key);
      componentSpecBuilder.addMethod(
          MethodSpec.methodBuilder(provisionMethodName)
              .addModifiers(Modifier.PUBLIC)
              .returns(key.getTypeName())
              .addStatement(
                  "return $L.$L()", CONTAINING_PACKAGED_INJECTOR_FIELD, provisionMethodName)
              .build());
      Preconditions.checkState(
          generatedBindingsForPackagedInjector.put(packagedInjectorClassName, key),
          String.format("Injector %s already provides %s.", packagedInjectorClassName, key));
    }
  }

  private void generateInheritedInjectionMethods(ClassName packagedInjectorClassName) {
    CoreInjectorInfo component =
        getComponentFromPackagedInjectorClassName(packagedInjectorClassName);
    if (componentTree.get(component) == null) {
      return;
    }
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    ClassName containingPackagedInjectorClassName =
        getInjectorNameOfScope(packagedInjectorClassName, componentTree.get(component).getScope());
    for (ClassName injectedClassName :
        injectedClassNamesForPackagedInjector.get(containingPackagedInjectorClassName)) {
      componentSpecBuilder.addMethod(
          MethodSpec.methodBuilder("inject")
              .addModifiers(Modifier.PUBLIC)
              .addParameter(injectedClassName, "arg")
              .addStatement("$L.inject(arg)", CONTAINING_PACKAGED_INJECTOR_FIELD)
              .build());
      injectedClassNamesForPackagedInjector.put(packagedInjectorClassName, injectedClassName);
    }
  }

  /**
   * For all bindings but single contributor of a multi-binding, which is handled by {@link
   * #getPackagedInjectorNameForDependencyInfo(BindingKey, DependencyInfo)} . For generic binding,
   * package of referencing class has access to both raw type and parameter types, though the
   * provision method generated for it will be duplicated in each such package.
   */
  private ClassName getInjectorNameFor(BindingKey key, TypeElement referencingClass) {
    ClassName result = null;
    TypeElement scope = scopeCalculator.calculate(key);
    DependencyInfo dependencyInfo =
        Iterables.getFirst(utils.getDependencyInfo(dependencies, key), null);
    // messager.printMessage(Kind.NOTE, "getInjectorFor: " + key + " dependencyInfo: "
    // + dependencyInfo);

    String packageString = null;
    boolean isMultiBinding = false;
    if (dependencyInfo != null) {
      isMultiBinding = dependencyInfo.isMultiBinding();
      Preconditions.checkNotNull(
          dependencyInfo.getSourceClassElement(),
          "DependencyInfo without source class? " + dependencyInfo);
      packageString =
          utils.getPackage(dependencyInfo.getSourceClassElement()).getQualifiedName().toString();
    } else if (utils.isProviderOrLazy(key)) {
      result =
          getInjectorNameFor(utils.getElementKeyForParameterizedBinding(key), referencingClass);
    } else {
      // TODO: clean this.
      messager.printMessage(Kind.ERROR, "dependencies not found for key: " + key);
      DependencyInfo genericDependencyInfo = utils.getDependencyInfoByGeneric(dependencies, key);
      if (genericDependencyInfo != null) {
        packageString = utils.getPackageString(referencingClass);
      } else {
        errors.add(String.format("Cannot resolve %s.", key));
        throw new RuntimeException(errors.toString());
      }
    }

    if (result == null) {
      String simpleName =
          isMultiBinding
              ? utils.getMultiBindingInjectorSimpleName(scope)
              : utils.getPackagedInjectorSimpleName(scope);
      result = ClassName.get(isMultiBinding ? topLevelPackageString : packageString, simpleName);
    }

    return result;
  }

  /** From the given dependencyInfo contributes to given key. */
  private ClassName getPackagedInjectorNameForDependencyInfo(
      BindingKey key, DependencyInfo dependencyInfo) {
    TypeElement scope = scopeCalculator.calculate(key);
    return getPackagedInjectorNameForDependencyInfo(scope, dependencyInfo);
  }

  /** From the given dependencyInfo contributes to given key. */
  private ClassName getPackagedInjectorNameForDependencyInfo(
      TypeElement scope, DependencyInfo dependencyInfo) {
    return ClassName.get(
        utils.getPackageString(dependencyInfo.getSourceClassElement()),
        utils.getPackagedInjectorSimpleName(scope));
  }

  private ClassName getInjectorOfScope(
      BindingKey key, TypeElement referencingClass, TypeElement scope) {
    ClassName injectorClassName = getInjectorNameFor(key, referencingClass);
    return getInjectorNameOfScope(injectorClassName, scope);
  }

  private ClassName getPackagedInjectorNameOfScope(String packageString, TypeElement scope) {
    return ClassName.get(packageString, utils.getPackagedInjectorSimpleName(scope));
  }

  private void generateProvisionMethodIfNeeded(BindingKey key, TypeElement referencingClass) {
    messager.printMessage(
        Kind.NOTE,
        TAG + ".generateProvisionMethodIfNeeded, key: " + key + " ref: " + referencingClass);
    // TODO: put all the dependency handling logic in one place
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfosHandlingBox(dependencies, key);

    DependencyInfo dependencyInfo =
        dependencyInfos == null ? null : Iterables.getFirst(dependencyInfos, null);
    messager.printMessage(
        Kind.NOTE, TAG + ".generateProvisionMethodIfNeeded, dI: " + dependencyInfo);
    // TODO: handle boxing better.
    if (dependencyInfo != null) {
      key = dependencyInfo.getDependant();
    }
    ClassName packagedInjectorClassName = getInjectorNameFor(key, referencingClass);
    Builder injectorSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    if (!generatedBindingsForPackagedInjector.put(packagedInjectorClassName, key)) {
      return;
    }

    // messager.printMessage(Kind.NOTE, "generateProvisionMethodIfNeeded, DependencyInfo: " +
    // dependencyInfo);
    // messager.printMessage(Kind.NOTE, "generateProvisionMethodIfNeeded, scope: " +
    // scopeCalculator.calculate(key));
    boolean scoped = explicitScopes.contains(key);
    String suffix = scoped ? UNSCOPED_SUFFIX : "";
    /**
     * TODO: revist this and handle it in a consistent way with the ones below. This is related with
     * {@link Utils#getDependencyInfo(SetMultimap, BindingKey)}.
     */
    if (utils.isOptional(key)
        && utils.isBindsOptionalOf(utils.getDependencyInfo(dependencies, key))) {
      generateProvisionMethodForBindsOptionalOf(key, referencingClass, suffix);
    } else if (dependencyInfo != null) {
      switch (dependencyInfo.getType()) {
        case SET:
        case SET_VALUES:
          // TODO: revisit scoped
          scoped = false;
          generateProvisionMethodForSet(key, referencingClass, "");
          break;
        case MAP:
          // TODO: refactor here and below.
          // TODO: revisit scoped
          scoped = false;
          generateProvisionMethodForMap(key, referencingClass, "");
          break;
        case UNIQUE:
          switch (dependencyInfo.getDependencySourceType()) {
            case MODULE:
              generateUniqueTypeProvisionMethodFromModule(key, suffix);
              break;
            case CTOR_INJECTED_CLASS:
              generateProvisionMethodFromClass(key, referencingClass, suffix);
              break;
            case DAGGER_MEMBERS_INJECTOR:
              generateProvisionMethodForDaggerMembersInjector(key, referencingClass, suffix);
              break;
            case COMPONENT_DEPENDENCIES_METHOD:
              generateProvisionMethodFromComponentDependency(key, referencingClass);
              break;
            case BINDS_INTANCE:
            case COMPONENT_DEPENDENCIES_ITSELF:
            case EITHER_COMPONENT:
              generateProvisionMethodFromBindsInstance(key, referencingClass);
              break;
            case EITHER_COMPONENT_BUILDER:
              generateProvisionMethodForEitherComponentBuilder(key, referencingClass);
              break;
            default:
              throw new RuntimeException(
                  "Shouln't be here. dependencyInfo.dependencySourceType: "
                      + dependencyInfo.getDependencySourceType());
          }
          break;
        default:
          throw new RuntimeException("Unknown dependencyInfo.type: " + dependencyInfo.getType());
      }
    } else if (utils.isEitherComponentBuilder(processingEnv, key)) {
      // TODO: remove?
      errors.add("should not generate provision method for (sub)component builder");
    } else if (utils.isProviderOrLazy(key)) {
      generateProvisionMethodForProviderOrLazy(key, referencingClass, suffix);
    } else if (utils.isMap(key)) {
      generateProvisionMethodForMap(key, referencingClass, suffix);
    } else {
      DependencyInfo genericDependencyInfo = utils.getDependencyInfoByGeneric(dependencies, key);
      if (genericDependencyInfo != null) {
        if (genericDependencyInfo.getProvisionMethodElement() == null) {
          generateProvisionMethodFromClass(key, referencingClass, suffix);
        } else {
          errors.add(
              String.format(
                  "Generic provision method not supported yet: %s -> %s",
                  key, genericDependencyInfo));
        }
      } else {
        errors.add(String.format("Cannot resolve %s.", key));
        throw new RuntimeException();
      }
    }
    if (scoped) {
      generateField(injectorSpecBuilder, key);
      generateScopedProvisionMethod(injectorSpecBuilder, key);
    }
  }

  private void generateProvisionMethodForBindsOptionalOf(
      BindingKey key, TypeElement referencingClass, String suffix) {
    BindingKey elementKey = utils.getElementKeyForParameterizedBinding(key);
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, elementKey);
    boolean present = dependencyInfos != null;

    ClassName injectorClassName = getInjectorNameFor(key, referencingClass);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(key.getTypeName());
    onProvisionMethodStart(methodSpecBuilder, key);
    if (present) {
      methodSpecBuilder.addStatement("$T value", elementKey.getTypeName());
      DependencyInfo dependencyInfo = Iterables.getOnlyElement(dependencyInfos);
      generateProvisionMethodIfNeeded(elementKey, referencingClass);
      StringBuilder stringBuilder = new StringBuilder("value = ");
      addCallingProvisionMethod(stringBuilder, elementKey, referencingClass, injectorClassName);
      methodSpecBuilder.addStatement(stringBuilder.toString());
      methodSpecBuilder.addStatement("return $T.of(value)", ClassName.get(Optional.class));

    } else {
      methodSpecBuilder.addStatement("return $T.absent()", ClassName.get(Optional.class));
    }
    onProvisionMethodEnd(methodSpecBuilder, key);

    Builder componentSpecBuilder =
        getInjectorTypeSpecBuilder(getInjectorNameFor(key, referencingClass));
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private void onProvisionMethodStart(MethodSpec.Builder methodSpecBuilder, BindingKey key) {
    if (!LOG_PROVISION_METHOD_ENABLED) {
      return;
    }
    // methodSpecBuilder.addStatement(
    //     "logger.atInfo().log($S)", "providing starts before lock: " + key);
    // methodSpecBuilder
    //     .addStatement(
    //         "$L.$L.$L.lock()",
    //         LOCK_HOLDER_PACKAGE_STRING,
    //         LOCK_HOLDER_CLASS_STRING,
    //         LOCK_HOLDER_FIELD_STRIN);
    methodSpecBuilder.beginControlFlow("try");
    methodSpecBuilder.addStatement("logger.atInfo().log($S)", "{ providing starts: " + key);
  }

  private void onProvisionMethodEnd(MethodSpec.Builder methodSpecBuilder, BindingKey key) {
    if (!LOG_PROVISION_METHOD_ENABLED) {
      return;
    }
    methodSpecBuilder
        .nextControlFlow("finally")
        .addStatement("logger.atInfo().log($S)", "} providing ends: " + key);
    // methodSpecBuilder.addStatement(
    //     "$L.$L.$L.unlock()",
    //     LOCK_HOLDER_PACKAGE_STRING,
    //     LOCK_HOLDER_CLASS_STRING,
    //     LOCK_HOLDER_FIELD_STRIN);
    methodSpecBuilder.endControlFlow();
  }

  private void generateProvisionMethodFromComponentDependency(
      BindingKey key, TypeElement referencingClass) {
    generateProvisionMethodForThoseFromTopLevel(key, referencingClass);
  }

  /**
   * for {@link DependencySourceType#COMPONENT_DEPENDENCIES_ITSELF}, {link {@link
   * DependencySourceType#COMPONENT_DEPENDENCIES_METHOD} and {@link
   * DependencySourceType#BINDS_INTANCE}, {@link DependencySourceType#EITHER_COMPONENT}, {@link
   * DependencySourceType#EITHER_COMPONENT_BUILDER}
   */
  private void generateProvisionMethodForThoseFromTopLevel(
      BindingKey key, TypeElement referencingClass) {
    ClassName injectorClassName = getInjectorNameFor(key, referencingClass);
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(injectorClassName);
    DependencyInfo dependencyInfo =
        Iterables.getOnlyElement(utils.getDependencyInfosHandlingBox(dependencies, key));
    DependencySourceType dependencySourceType = dependencyInfo.getDependencySourceType();

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key))
            .addModifiers(Modifier.PUBLIC)
            .returns(key.getTypeName());

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result", key.getTypeName());
    StringBuilder builder = new StringBuilder("result = ");
    builder.append(TOP_LEVEL_INJECTOR_FIELD).append(".");
    switch (dependencySourceType) {
      case COMPONENT_DEPENDENCIES_METHOD:
        builder
            .append(utils.getSourceCodeName(dependencyInfo.getSourceClassElement()))
            .append(".")
            .append(dependencyInfo.getProvisionMethodElement().getSimpleName())
            .append("()");
        break;
      case COMPONENT_DEPENDENCIES_ITSELF:
        // fall through
      case BINDS_INTANCE:
        builder.append(utils.getSourceCodeNameHandlingBox(key, dependencies));
        break;
      case EITHER_COMPONENT:
        builder.deleteCharAt(builder.length() - 1);
        break;
      case EITHER_COMPONENT_BUILDER:
        TypeElement eitherComponentBuilder =
            utils.getTypeElementForClassName((ClassName) key.getTypeName());
        builder.append(utils.getGetMethodName(eitherComponentBuilder)).append("()");
        break;
      default:
        throw new RuntimeException("Unexpected dependencySourceType from dI: " + dependencyInfo);
    }

    methodSpecBuilder.addStatement(builder.toString());

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);

    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private void generateProvisionMethodFromBindsInstance(
      BindingKey key, TypeElement referencingClass) {
    generateProvisionMethodForThoseFromTopLevel(key, referencingClass);
  }

  // TODO: Refactor, so far this happens to work for provision methods from component dependencies.
  private void generateUniqueTypeProvisionMethodFromModule(BindingKey key, String suffix) {
    DependencyInfo dependencyInfo =
        Iterables.getOnlyElement(utils.getDependencyInfosHandlingBox(dependencies, key));

    Preconditions.checkNotNull(dependencyInfo.getProvisionMethodElement());
    TypeMirror returnType = dependencyInfo.getProvisionMethodElement().getReturnType();
    ClassName injectorClassName = getPackagedInjectorNameForDependencyInfo(key, dependencyInfo);
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(injectorClassName);
    BindingKey returnKey = BindingKey.get(returnType, key.getQualifier());
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, returnKey) + suffix);
    methodSpecBuilder
        .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
        .returns(TypeName.get(returnType));

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result", returnType);
    addNewStatementToMethodSpec(
        key, dependencyInfo, injectorClassName, methodSpecBuilder, "result");

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
    // messager.printMessage(Kind.NOTE, String.format(
    // "generateUniqueTypeProvisionMethodFromModule: \n key: %s, \n injector: %s, \n method: %s.",
    // key, injectorClassName, methodSpecBuilder.build()));
  }

  // If dependencyInfo is not null, then it is a contributor to set binding.
  private void generateSetTypeProvisionMethodForPackage(
      BindingKey key, Set<DependencyInfo> dependencyInfos, String suffix) {
    messager.printMessage(
        Kind.NOTE,
        TAG + ".generateSetTypeProvisionMethodForPackage: key " + key + " dI: " + dependencyInfos);
    Preconditions.checkArgument(
        !dependencyInfos.isEmpty(), String.format("Empty dependencyInfo for key: %s", key));
    DependencyInfo dependencyInfo = Iterables.getFirst(dependencyInfos, null);
    TypeName returnType = key.getTypeName();
    ClassName injectorClassName = getPackagedInjectorNameForDependencyInfo(key, dependencyInfo);
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(injectorClassName);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key) + suffix);
    methodSpecBuilder.addModifiers(Modifier.PUBLIC).returns(returnType);

    methodSpecBuilder.addStatement("$T result = new $T<>()", returnType, HashSet.class);
    for (DependencyInfo di : dependencyInfos) {
      if (utils.isMultibindsMethod(di.getProvisionMethodElement())) {
        continue;
      }

      methodSpecBuilder.beginControlFlow("");
      Preconditions.checkNotNull(di.getProvisionMethodElement());
      TypeName contributorType = TypeName.get(di.getProvisionMethodElement().getReturnType());
      methodSpecBuilder.addStatement("$T contributor", contributorType);
      addNewStatementToMethodSpec(key, di, injectorClassName, methodSpecBuilder, "contributor");
      if (di.getType().equals(SET)) {
        methodSpecBuilder.addStatement("result.add(contributor)");
      } else {
        Preconditions.checkState(di.getType().equals(SET_VALUES));
        methodSpecBuilder.addStatement("result.addAll(contributor)");
      }

      methodSpecBuilder.endControlFlow();
    }
    methodSpecBuilder.addStatement("return result");
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
    // messager.printMessage(Kind.NOTE, String.format(
    // "generateSetTypeProvisionMethodFromModule: \n key: %s, \n injector: %s, \n method: %s.",
    // key, injectorClassName, methodSpecBuilder.build()));
  }

  // it is a contributor to map binding.
  /** TODO: revisit the logic to handle scoped multi bindings. */
  private void generateMapTypeProvisionMethodForPackage(
      final BindingKey key, Set<DependencyInfo> dependencyInfos, String suffix) {
    messager.printMessage(
        Kind.NOTE,
        TAG + ".generateMapTypeProvisionMethodForPackage: key " + key + " di " + dependencyInfos);
    Preconditions.checkArgument(
        !dependencyInfos.isEmpty(), String.format("Empty dependencyInfo for key: %s", key));
    TypeElement scope = scopeCalculator.calculate(key);
    DependencyInfo dependencyInfo = Iterables.getFirst(dependencyInfos, null);
    TypeName returnType = key.getTypeName();
    ClassName injectorClassName = getPackagedInjectorNameForDependencyInfo(key, dependencyInfo);
    TypeSpec.Builder packagedInjectorSpecBuilder = getInjectorTypeSpecBuilder(injectorClassName);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key) + suffix);
    methodSpecBuilder.addModifiers(Modifier.PUBLIC).returns(returnType);

    methodSpecBuilder.addStatement("$T result = new $T<>()", returnType, HashMap.class);
    TypeName mapKeyType = ((ParameterizedTypeName) returnType).typeArguments.get(0);
    TypeName mapValueType = ((ParameterizedTypeName) returnType).typeArguments.get(1);
    BindingKey mapValueKey = BindingKey.get(mapValueType);
    methodSpecBuilder.addStatement("$T mapKey", mapKeyType);
    methodSpecBuilder.addStatement("$T mapValue", mapValueType);
    for (DependencyInfo di : dependencyInfos) {
      if (utils.isMultibindsMethod(di.getProvisionMethodElement())) {
        continue;
      }

      AnnotationMirror mapKeyMirror =
          utils.getAnnotationMirrorWithMetaAnnotation(di.getProvisionMethodElement(), MapKey.class);
      AnnotationValue unwrapValueAnnotationValue =
          utils.getAnnotationValue(elements, mapKeyMirror, "unwrapValue");
      if (unwrapValueAnnotationValue != null
          && !((boolean) unwrapValueAnnotationValue.getValue())) {
        errors.add(
            String.format("unwrapValue = false not supported yet. Consider using set binding."));
        return;
      }
      AnnotationValue mapKey = utils.getAnnotationValue(elements, mapKeyMirror, "value");
      messager.printMessage(
          Kind.NOTE, TAG + ".generateMapTypeProvisionMethodForPackage: mapKey  " + mapKey);
      methodSpecBuilder.addStatement("mapKey = ($T) $L", mapKeyType, mapKey);
      if (utils.isMapWithBuiltinValueType(key)) {
        methodSpecBuilder.addStatement(
            "mapValue = $L",
            createAnonymousBuiltinTypeForMultiBinding(injectorClassName, mapValueKey, scope, di));
      } else {
        addNewStatementToMethodSpec(scope, di, injectorClassName, methodSpecBuilder, "mapValue");
      }
      methodSpecBuilder.addStatement("result.put(mapKey, mapValue)");
    }
    methodSpecBuilder.addStatement("return result");
    packagedInjectorSpecBuilder.addMethod(methodSpecBuilder.build());
    // messager.printMessage(Kind.NOTE, String.format(
    // "generateSetTypeProvisionMethodFromModule: \n key: %s, \n injector: %s, \n method: %s.",
    // key, injectorClassName, methodSpecBuilder.build()));
  }

  /**
   * Only applicable to bindings from modules. This is just a convenience method. Use the other
   * {@link #addNewStatementToMethodSpec} if scope can not be calculated from key, e.g., value key
   * for Map<String, Provider<Foo>>.
   */
  private void addNewStatementToMethodSpec(
      BindingKey key,
      DependencyInfo dependencyInfo,
      ClassName packagedInjectorClassName,
      MethodSpec.Builder methodSpecBuilder,
      String newVarName) {
    TypeElement scope = scopeCalculator.calculate(key);
    addNewStatementToMethodSpec(
        scope, dependencyInfo, packagedInjectorClassName, methodSpecBuilder, newVarName);
  }

  private void addNewStatementToMethodSpec(
      TypeElement scope,
      DependencyInfo dependencyInfo,
      ClassName packagedInjectorClassName,
      MethodSpec.Builder methodSpecBuilder,
      String newVarName) {
    messager.printMessage(
        Kind.NOTE,
        TAG
            + ".addNewStatementToMethodSpec: "
            + " scope: "
            + scope
            + " dependencyInfo : "
            + dependencyInfo);
    ExecutableElement provisionMethodElement = dependencyInfo.getProvisionMethodElement();
    Preconditions.checkNotNull(provisionMethodElement);

    // for @Binds
    if (utils.isBindsMethod(provisionMethodElement)) {
      StringBuilder stringBuilder = new StringBuilder();
      addCallingProvisionMethod(
          stringBuilder,
          Iterables.getOnlyElement(dependencyInfo.getDependencies()),
          dependencyInfo.getSourceClassElement(),
          packagedInjectorClassName);
      methodSpecBuilder.addStatement("$L = $L", newVarName, stringBuilder.toString());
    } else {
      boolean isStaticMethod =
          dependencyInfo.getProvisionMethodElement().getModifiers().contains(Modifier.STATIC);
      StringBuilder builder = new StringBuilder(isStaticMethod ? "$L = $T.$N(" : "$L = $N().$N(");

      if (dependencyInfo.getDependencies().size() > 0) {
        for (BindingKey dependentKey :
            utils.getDependenciesFromExecutableElement(provisionMethodElement)) {
          generateCodeForDependency(
              dependentKey,
              dependencyInfo.getSourceClassElement(),
              packagedInjectorClassName,
              builder);
        }
        builder.delete(builder.length() - 2, builder.length());
      }
      builder.append(")");
      methodSpecBuilder.addStatement(
          builder.toString(),
          newVarName,
          isStaticMethod
              ? TypeName.get(dependencyInfo.getSourceClassElement().asType())
              : getGetModuleMethod(scope, dependencyInfo),
          provisionMethodElement.getSimpleName());
    }
  }

  private MethodSpec getGetModuleMethod(BindingKey key, DependencyInfo dependencyInfo) {
    TypeElement scope = scopeCalculator.calculate(key);
    return getGetModuleMethod(scope, dependencyInfo);
  }

  private MethodSpec getGetModuleMethod(TypeElement scope, DependencyInfo dependencyInfo) {
    Preconditions.checkArgument(
        dependencyInfo.getProvisionMethodElement() != null,
        String.format("Expect one from module but get %s.", dependencyInfo));
    TypeElement module = dependencyInfo.getSourceClassElement();
    ClassName packagedInjectorClassName =
        getPackagedInjectorNameForDependencyInfo(scope, dependencyInfo);
    if (!modulesWithGetter.containsKey(packagedInjectorClassName)) {
      modulesWithGetter.put(packagedInjectorClassName, new HashMap<ClassName, MethodSpec>());
    }
    if (!modulesWithGetter.get(packagedInjectorClassName).containsKey(ClassName.get(module))) {
      generateGetModuleMethod(scope, dependencyInfo);
    }

    return modulesWithGetter.get(packagedInjectorClassName).get(ClassName.get(module));
  }

  /** Creates get_Foo_Module() for FooModule. */
  private void generateGetModuleMethod(BindingKey key, DependencyInfo dependencyInfo) {
    TypeElement scope = scopeCalculator.calculate(key);
    generateGetModuleMethod(scope, dependencyInfo);
  }

  /** Creates get_Foo_Module() for FooModule. */
  private void generateGetModuleMethod(TypeElement scope, DependencyInfo dependencyInfo) {
    messager.printMessage(Kind.NOTE, TAG + ".generateGetModuleMethod: dI " + dependencyInfo);
    Preconditions.checkArgument(
        dependencyInfo.getProvisionMethodElement() != null,
        String.format("Expect one from module but get %s.", dependencyInfo));
    TypeElement module = dependencyInfo.getSourceClassElement();
    TypeName moduleTypeName = TypeName.get(module.asType());
    ClassName packagedInjectorClassName =
        getPackagedInjectorNameForDependencyInfo(scope, dependencyInfo);
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    String moduleCanonicalNameConverted = utils.getQualifiedName(module).replace(".", "_");

    componentSpecBuilder.addField(moduleTypeName, moduleCanonicalNameConverted, Modifier.PRIVATE);

    MethodSpec.Builder methodBuilder =
        MethodSpec.methodBuilder(utils.getGetMethodName(module))
            .addModifiers(Modifier.PRIVATE)
            .addStatement("$T result = $N", module, moduleCanonicalNameConverted)
            .returns(moduleTypeName);
    methodBuilder.beginControlFlow("if (result == null)");
    if (!isPassedModule(module)) {
      methodBuilder.addStatement("result = $N = new $T()", moduleCanonicalNameConverted, module);
    } else {
      methodBuilder.addStatement(
          "result = $N = $L.$N()",
          moduleCanonicalNameConverted,
          TOP_LEVEL_INJECTOR_FIELD,
          utils.getGetMethodName(module));
    }
    methodBuilder.endControlFlow();
    methodBuilder.addStatement("return result");
    MethodSpec methodSpec = methodBuilder.build();
    componentSpecBuilder.addMethod(methodSpec);

    messager.printMessage(
        Kind.NOTE,
        TAG
            + ".generateGetModuleMethod: methodSpec "
            + methodSpec
            + " packagedInjector "
            + packagedInjectorClassName);
    modulesWithGetter.get(packagedInjectorClassName).put(ClassName.get(module), methodSpec);
  }

  /** Returns if the module is passed in to top level injector. */
  private boolean isPassedModule(TypeElement module) {
    return nonNullaryCtorModules.values().contains(module)
        || nonNullaryCtorUnscopedModules.contains(module);
  }

  /**
   * For key like javax.inject.Provider<Foo> and dagger.Lazy<Foo>. Qualifier, if presented, will
   * also apply to element binding.
   */
  private void generateProvisionMethodForProviderOrLazy(
      BindingKey key, TypeElement referencingClass, String suffix) {
    // messager.printMessage(Kind.NOTE, String.format(
    // "generateProvisionMethodForProviderOrLazy: key %s, referencingClass: %s, suffix : %s.", key,
    // referencingClass, suffix));

    ClassName injectorClassName = getInjectorNameFor(key, referencingClass);
    TypeSpec anonymousTypeSpec =
        createAnonymousBuiltinTypeForUniqueBinding(injectorClassName, key, referencingClass);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(key.getTypeName());

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result = $L", key.getTypeName(), anonymousTypeSpec);

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);

    Builder componentSpecBuilder =
        getInjectorTypeSpecBuilder(getInjectorNameFor(key, referencingClass));
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private TypeSpec createAnonymousBuiltinTypeForUniqueBinding(
      ClassName leafInjectorClassName, BindingKey key, TypeElement referencingClass) {
    return createAnonymousBuiltinType(
        leafInjectorClassName, key, null /* scope */, referencingClass, null /* dependencyInfo */);
  }

  private TypeSpec createAnonymousBuiltinTypeForMultiBinding(
      ClassName leafInjectorClassName,
      BindingKey key,
      TypeElement scope,
      DependencyInfo dependency) {
    return createAnonymousBuiltinType(
        leafInjectorClassName, key, scope, null /* referencingClass */, dependency);
  }

  /**
   * Generate for either unique a binding or a contributor to a multi-binding. DependencyInfo is
   * null for unique one and non-null or multi-binding one. ReferencingClass is the opposite. For
   * multi-binding, referencing class is the module in dependency. Scope is null for unique binding.
   */
  private TypeSpec createAnonymousBuiltinType(
      ClassName leafInjectorClassName,
      BindingKey key,
      @Nullable TypeElement scope,
      @Nullable TypeElement referencingClass,
      @Nullable DependencyInfo dependencyInfo) {
    Preconditions.checkArgument(key.getTypeName() instanceof ParameterizedTypeName);
    boolean isMultiBinding = dependencyInfo != null;
    if (isMultiBinding) {
      Preconditions.checkArgument(referencingClass == null);
      Preconditions.checkNotNull(scope);
    } else {
      Preconditions.checkNotNull(referencingClass);
      Preconditions.checkArgument(scope == null);
    }
    TypeName rawTypeName = ((ParameterizedTypeName) key.getTypeName()).rawType;
    Preconditions.checkArgument(
        utils.isProviderOrLazy(key),
        String.format("Built-in binding expected(Provider or Lazy), but get %s", key));
    boolean isLazy = rawTypeName.equals(ClassName.get(Lazy.class));
    BindingKey elementKey = utils.getElementKeyForParameterizedBinding(key);
    Preconditions.checkNotNull(elementKey);
    if (!isMultiBinding) {
      generateProvisionMethodIfNeeded(elementKey, referencingClass);
    }
    MethodSpec.Builder builderForGet =
        MethodSpec.methodBuilder("get")
            .returns(elementKey.getTypeName())
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC);

    builderForGet.addStatement("$T var = null", elementKey.getTypeName());
    if (isLazy) {
      builderForGet.beginControlFlow("if (var == null)");
    }
    if (!isMultiBinding) {
      Set<DependencyInfo> dIs = utils.getDependencyInfosHandlingBox(dependencies, elementKey);
      if (dIs != null) {
        elementKey =
            Preconditions.checkNotNull(
                    Iterables.getFirst(dIs, null), "key: " + elementKey + " dI: " + dIs)
                .getDependant();
      }
      builderForGet.addStatement(
          "var = $N()", utils.getProvisionMethodName(dependencies, elementKey));
    } else {
      /**
       * TODO: revisit the logic here, current, for Provide, Lazy and Optional, the key != {@link
       * DependencyInfo#getDependant()}.
       */
      addNewStatementToMethodSpec(
          scope, dependencyInfo, leafInjectorClassName, builderForGet, "var");
    }
    if (isLazy) {
      builderForGet.endControlFlow();
    }
    builderForGet.addStatement("return var");

    TypeSpec result =
        TypeSpec.anonymousClassBuilder("")
            .addSuperinterface(key.getTypeName())
            .addMethod(builderForGet.build())
            .build();

    return result;
  }

  private void generateScopedProvisionMethod(Builder componentSpecBuilder, BindingKey key) {
    MethodSpec.Builder builder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key))
            .returns(key.getTypeName())
            .addModifiers(Modifier.PUBLIC);
    builder
        .addStatement("$T result = $N", key.getTypeName().box(), getFieldName(key))
        .beginControlFlow("if (result == null)")
        .addStatement("result = $N", getFieldName(key))
        .beginControlFlow("if (result == null)")
        .addStatement(
            "result = $L = $L()",
            getFieldName(key),
            utils.getProvisionMethodName(dependencies, key) + UNSCOPED_SUFFIX)
        .endControlFlow() // if
        .endControlFlow() // if
        .addStatement("return result");
    componentSpecBuilder.addMethod(builder.build());
  }

  private void generateField(Builder componentSpecBuilder, BindingKey key) {
    FieldSpec.Builder builder =
        FieldSpec.builder(key.getTypeName().box(), getFieldName(key), Modifier.PRIVATE);
    componentSpecBuilder.addField(builder.build());
  }

  private String getFieldName(BindingKey key) {
    return utils.getSourceCodeNameHandlingBox(key, dependencies);
  }

  private void generateProvisionMethodForSet(
      BindingKey key, TypeElement referencingClass, String suffix) {
    TypeSpec.Builder componentSpecBuilder =
        getInjectorTypeSpecBuilder(getInjectorNameFor(key, referencingClass));

    // messager.printMessage(Kind.NOTE, "generateProvisionMethodForSet: " + key +
    // " PackagedInjector: "
    // + getInjectorFor(key, referencingClass) + " SpecBuilder: " + componentSpecBuilder);
    ParameterizedTypeName type = (ParameterizedTypeName) key.getTypeName();
    Preconditions.checkArgument(type.rawType.equals(ClassName.get(Set.class)));
    TypeName elementType = Iterables.getOnlyElement(type.typeArguments);

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(type);

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result = new $T<>()", type, HashSet.class);
    methodSpecBuilder.addStatement("$T setVar", type);
    methodSpecBuilder.addStatement("$T elementVar", elementType);
    SetMultimap<PackageElement, DependencyInfo> packageToDependencyInfoMap = HashMultimap.create();
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, key);
    if (dependencyInfos != null) {
      for (DependencyInfo dependencyInfo : dependencyInfos) {
        packageToDependencyInfoMap.put(
            utils.getPackage(dependencyInfo.getSourceClassElement()), dependencyInfo);
      }
    }
    for (PackageElement pkg : packageToDependencyInfoMap.keySet()) {
      // messager.printMessage(Kind.NOTE, String.format("generateProvisionMethodForSet for %s from"
      // +
      // " %s", key, packageToDependencyInfoMap.get(pkg)));

      generateSetTypeProvisionMethodForPackage(key, packageToDependencyInfoMap.get(pkg), suffix);
      DependencyInfo dependencyInfo = Iterables.getFirst(packageToDependencyInfoMap.get(pkg), null);
      Preconditions.checkNotNull(
          dependencyInfo, String.format("no dependencyInfo for set key %s in module %s", key, pkg));
      ClassName packagedInjectorClassName =
          getPackagedInjectorNameForDependencyInfo(key, dependencyInfo);
      methodSpecBuilder.addStatement(
          "setVar = $L.$N().$N()",
          TOP_LEVEL_INJECTOR_FIELD,
          utils.getGetMethodName(packagedInjectorClassName),
          utils.getProvisionMethodName(dependencies, key));
      methodSpecBuilder.addStatement("result.addAll($L)", "setVar");
    }

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private void generateProvisionMethodForMap(
      final BindingKey key, TypeElement referencingClass, String suffix) {
    TypeSpec.Builder componentSpecBuilder =
        getInjectorTypeSpecBuilder(getInjectorNameFor(key, referencingClass));

    // messager.printMessage(Kind.NOTE, "generateProvisionMethodForSet: " + key +
    // " PackagedInjector: "
    // + getInjectorFor(key, referencingClass) + " SpecBuilder: " + componentSpecBuilder);
    ParameterizedTypeName type = (ParameterizedTypeName) key.getTypeName();
    Preconditions.checkArgument(type.rawType.equals(ClassName.get(Map.class)));

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(type);

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result = new $T<>()", type, HashMap.class);
    methodSpecBuilder.addStatement("$T packagedMap", type);
    SetMultimap<PackageElement, DependencyInfo> packageToDependencyInfoMap = HashMultimap.create();
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, key);
    Preconditions.checkNotNull(
        dependencyInfos, String.format("dependencyInfo not found for key: %s", key));
    for (DependencyInfo dependencyInfo : utils.getDependencyInfo(dependencies, key)) {
      packageToDependencyInfoMap.put(
          utils.getPackage(dependencyInfo.getSourceClassElement()), dependencyInfo);
    }
    for (PackageElement pkg : packageToDependencyInfoMap.keySet()) {
      // messager.printMessage(Kind.NOTE, String.format("generateProvisionMethodForSet for %s from"
      // +
      // " %s", key, packageToDependencyInfoMap.get(pkg)));

      generateMapTypeProvisionMethodForPackage(key, packageToDependencyInfoMap.get(pkg), suffix);
      DependencyInfo dependencyInfo = Iterables.getFirst(packageToDependencyInfoMap.get(pkg), null);
      Preconditions.checkNotNull(
          dependencyInfo, String.format("no dependencyInfo for set key %s in module %s", key, pkg));
      ClassName packagedInjectorClassName =
          getPackagedInjectorNameForDependencyInfo(key, dependencyInfo);
      methodSpecBuilder.addStatement(
          "packagedMap = $L.$N().$N()",
          TOP_LEVEL_INJECTOR_FIELD,
          utils.getGetMethodName(packagedInjectorClassName),
          utils.getProvisionMethodName(dependencies, key));
      methodSpecBuilder.addStatement("result.putAll($L)", "packagedMap");
    }

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private void generateInjectionMethod(BindingKey key, TypeElement referencingClass) {
    generateInjectionMethod(
        utils.getClassFromKey(key),
        getInjectorNameFor(key, referencingClass),
        "inject",
        true);
  }

  private void generateInjectionMethod(TypeElement cls, TypeElement scope) {
    generateInjectionMethod(
        cls, getPackagedInjectorNameOfScope(utils.getPackageString(cls), scope), "inject", true);
  }

  private void generateInjectionMethod(
      TypeElement cls, ClassName packagedInjectorClassName, String methodName, boolean isPublic) {
    if (!injectedClassNamesForPackagedInjector.put(packagedInjectorClassName, ClassName.get(cls))) {
      return;
    }

    // messager.printMessage(Kind.NOTE,
    // String.format("generateInjectionMethod. cls: %s, injector: %s, method: %s", cls,
    // packagedInjectorClassName, methodName));

    Builder componentSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(methodName)
            .addModifiers(isPublic ? Modifier.PUBLIC : Modifier.PRIVATE)
            .addParameter(ClassName.get(cls), "arg");

    // Inject closest ancestor first.
    TypeElement clsClosestInjectAncestor = utils.getSuper(cls);
    while (clsClosestInjectAncestor != null
        && !utils.hasInjectedFieldsOrMethods(clsClosestInjectAncestor, processingEnv)) {
      clsClosestInjectAncestor = utils.getSuper(clsClosestInjectAncestor);
    }
    if (clsClosestInjectAncestor != null) {
      TypeElement scope =
          getComponentFromPackagedInjectorClassName(packagedInjectorClassName).getScope();
      generateInjectionMethod(clsClosestInjectAncestor, scope);
      ClassName ancestorPackagedInjector =
          getPackagedInjectorNameOfScope(utils.getPackageString(clsClosestInjectAncestor), scope);
      StringBuilder stringBuilder = new StringBuilder();
      if (!ancestorPackagedInjector.equals(packagedInjectorClassName)) {
        stringBuilder.append(TOP_LEVEL_INJECTOR_FIELD).append(".$N().");
      }
      stringBuilder.append("inject(($T) arg)");
      if (!ancestorPackagedInjector.equals(packagedInjectorClassName)) {
        methodSpecBuilder.addStatement(
            stringBuilder.toString(),
            utils.getGetMethodName(ancestorPackagedInjector),
            ClassName.get(clsClosestInjectAncestor));
      } else {
        // TODO(freeman): ClassName.get() removed the type parameters for now. Support it.
        methodSpecBuilder.addStatement(
            stringBuilder.toString(), ClassName.get(clsClosestInjectAncestor));
      }
    }

    for (VariableElement field : utils.getInjectedFields(cls, processingEnv)) {
      // messager.printMessage(Kind.NOTE, "generateInjectionMethod. field: " + field);
      TypeMirror fieldType = field.asType();
      AnnotationMirror fieldQualifier = utils.getQualifier(field);
      BindingKey fieldKey = BindingKey.get(fieldType, fieldQualifier);
      StringBuilder stringBuilder =
          new StringBuilder("arg.").append(field.getSimpleName()).append(" = ");
      addCallingProvisionMethod(stringBuilder, fieldKey, cls, packagedInjectorClassName);
      methodSpecBuilder.addStatement(stringBuilder.toString());
    }

    for (ExecutableElement method : utils.getInjectedMethods(cls, processingEnv)) {
      // messager.printMessage(Kind.NOTE, "generateInjectionMethod. method: " + method);
      StringBuilder builder = new StringBuilder("arg.").append(method.getSimpleName()).append("(");
      List<BindingKey> methodArgs = utils.getDependenciesFromExecutableElement(method);
      if (methodArgs.size() > 0) {
        for (BindingKey dependentKey : methodArgs) {
          addCallingProvisionMethod(builder, dependentKey, cls, packagedInjectorClassName);
          builder.append(", ");
        }
        builder.delete(builder.length() - 2, builder.length());
      }
      builder.append(")");
      methodSpecBuilder.addStatement(builder.toString());
    }
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  /** Adds "[topLevelInjector.getxxxPackagedInjector.]getxxx()" to the builder. */
  private void addCallingProvisionMethod(
      StringBuilder stringBuilder,
      BindingKey key,
      TypeElement referencingClass,
      ClassName packagedInjectorClassName) {
    generateProvisionMethodIfNeeded(key, referencingClass);
    ClassName originalPackagedInjector = getInjectorNameFor(key, referencingClass);
    CoreInjectorInfo givenComponent =
        getComponentFromPackagedInjectorClassName(packagedInjectorClassName);
    ClassName targetPackagedInjectorClassName =
        getInjectorNameOfScope(originalPackagedInjector, givenComponent.getScope());
    if (!targetPackagedInjectorClassName.equals(packagedInjectorClassName)) {
      // messager.printMessage(Kind.NOTE, "addCallingProvisionMethod. packageInjector: " +
      // packagedInjectorClassName
      // + " field key: " + key);
      stringBuilder
          .append(TOP_LEVEL_INJECTOR_FIELD)
          .append(".")
          .append(utils.getGetMethodName(targetPackagedInjectorClassName))
          .append("().");
    }
    stringBuilder.append(utils.getProvisionMethodName(dependencies, key)).append("()");
  }

  /** Generic is handled. */
  private void generateProvisionMethodFromClass(
      BindingKey key, TypeElement referencingClass, String suffix) {
    // messager.printMessage(Kind.NOTE,
    // "generateProvisionMethodFromClass. key: " + key + " referencingClass: " +
    // referencingClass);
    ClassName packagedInjectorClassName = getInjectorNameFor(key, referencingClass);
    TypeSpec.Builder injectorSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);

    TypeElement cls = utils.getClassFromKey(key);

    List<BindingKey> dependencyKeys =
        utils.getCtorDependencies(dependencies, key);
    // TODO: clean this.
    // if (key.getTypeName() instanceof ParameterizedTypeName) {
    //   messager.printMessage(Kind.ERROR, "shouldn't be here :" + key);
    //   List<BindingKey> specializedKeys = new ArrayList<>();
    //   Map<TypeVariableName, TypeName> map =
    //       utils.getMapFromTypeVariableToSpecialized((ParameterizedTypeName) key.getTypeName(),
    //           (ParameterizedTypeName) TypeName.get(cls.asType()));
    //   for (BindingKey k : dependencyKeys) {
    //     specializedKeys.add(utils.specializeIfNeeded(k, map));
    //   }
    //   dependencyKeys = specializedKeys;
    // }

    // messager.printMessage(Kind.NOTE, "generateProvisionMethodFromClass. dependencyKeys: " +
    // dependencyKeys);

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key) + suffix);
    methodSpecBuilder
        .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
        .returns(key.getTypeName());

    onProvisionMethodStart(methodSpecBuilder, key);

    StringBuilder builder = new StringBuilder("$T result = new $T(");
    if (dependencyKeys.size() > 0) {
      for (BindingKey dependencyKey : dependencyKeys) {
        // messager.printMessage(Kind.NOTE, "generateProvisionMethodFromClass. dependencyKey: "
        // + dependencyKey);
        generateCodeForDependency(dependencyKey, cls, packagedInjectorClassName, builder);
      }
      builder.delete(builder.length() - 2, builder.length());
    }
    builder.append(")");
    methodSpecBuilder.addStatement(builder.toString(), key.getTypeName(), key.getTypeName());

    if (utils.hasInjectedFieldsOrMethodsRecursively(cls, processingEnv)) {
      // messager.printMessage(Kind.NOTE, "generateProvisionMethodFromClass. hasInjected");
      generateInjectionMethod(key, referencingClass);
      methodSpecBuilder.addStatement("inject(result)");
    }

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);
    injectorSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private void generateProvisionMethodForDaggerMembersInjector(
      BindingKey key, TypeElement referencingClass, String suffix) {
    // messager.printMessage(Kind.NOTE,
    // "generateProvisionMethodForDaggerMembersInjector. key: " + key + " referencingClass: " +
    // referencingClass);
    TypeElement scope = scopeCalculator.calculate(key);
    ClassName packagedInjectorClassName = getInjectorNameFor(key, referencingClass);
    TypeSpec.Builder injectorSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);

    BindingKey childKey = utils.getElementKeyForParameterizedBinding(key);

    TypeName childTypeName = childKey.getTypeName();
    String parameterSourceCodeName = utils.getSourceCodeName(childTypeName);
    MethodSpec.Builder injectMethodSpecBuilder =
        MethodSpec.methodBuilder("injectMembers")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(childTypeName, parameterSourceCodeName)
            .addAnnotation(Override.class);

    generateInjectionMethod(childKey, scope);
    ClassName childInjectorClassName = getInjectorNameFor(childKey, scope);
    if (childInjectorClassName != packagedInjectorClassName) {
      injectMethodSpecBuilder.addStatement(
          "$L.$N().$L($L)",
          TOP_LEVEL_INJECTOR_FIELD,
          utils.getGetMethodName(childInjectorClassName),
          "inject",
          parameterSourceCodeName);
    } else {
      injectMethodSpecBuilder.addStatement("$L($L)", "inject", parameterSourceCodeName);
    }

    TypeSpec injectorType =
        TypeSpec.anonymousClassBuilder("")
            .addSuperinterface(key.getTypeName())
            .addMethod(injectMethodSpecBuilder.build())
            .build();

    MethodSpec.Builder provisionMethodSpecBuilder =
        MethodSpec.methodBuilder(utils.getProvisionMethodName(dependencies, key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(key.getTypeName());

    onProvisionMethodStart(provisionMethodSpecBuilder, key);

    provisionMethodSpecBuilder.addStatement("$T result = $L", key.getTypeName(), injectorType);

    provisionMethodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(provisionMethodSpecBuilder, key);
    // messager.printMessage(Kind.NOTE, "xxx: " + provisionMethodSpecBuilder.build());
    injectorSpecBuilder.addMethod(provisionMethodSpecBuilder.build());
  }

  private void generateProvisionMethodForEitherComponentBuilder(
      BindingKey key, TypeElement referencingClass) {
    // The top level injector must be the one that provides the wanted builder.
    generateProvisionMethodForThoseFromTopLevel(key, referencingClass);
  }

  /**
   * Generates calling code to top-level injector for (sub)comonent builders, othewise generates
   * methods and the calling code.
   */
  private void generateCodeForDependency(
      BindingKey key,
      TypeElement referencingClass,
      ClassName packagedInjectorClassName,
      StringBuilder builder) {
    generateProvisionMethodAndAppendAsParameter(
        key, referencingClass, packagedInjectorClassName, builder);
  }

  private void generateProvisionMethodAndAppendAsParameter(
      BindingKey key,
      TypeElement referencingClass,
      ClassName packagedInjectorClassName,
      StringBuilder builder) {
    TypeElement scope =
        getComponentFromPackagedInjectorClassName(packagedInjectorClassName).getScope();
    generateProvisionMethodIfNeeded(key, referencingClass);
    ClassName dependencyPackagedInjectorClassName =
        getInjectorOfScope(key, referencingClass, scope);
    if (!dependencyPackagedInjectorClassName.equals(packagedInjectorClassName)) {
      builder
          .append(TOP_LEVEL_INJECTOR_FIELD)
          .append(".")
          .append(utils.getGetMethodName(dependencyPackagedInjectorClassName))
          .append("().");
    }
    builder.append(utils.getProvisionMethodName(dependencies, key)).append("(), ");
  }

  /**
   * Returns {@link ClassName} of the injector in the same package as the give injectorClassName but
   * for the given scope.
   */
  private static ClassName getInjectorNameOfScope(ClassName injectorClassName, TypeElement scope) {
    String packageString = getPackageFromInjectorClassName(injectorClassName);
    boolean isMultiBindingInjector = Utils.isMultiBindingInjector(injectorClassName);
    String simpleName =
        isMultiBindingInjector
            ? Utils.getMultiBindingInjectorSimpleName(scope)
            : Utils.getPackagedInjectorSimpleName(scope);
    return ClassName.get(packageString, simpleName);
  }

  private void generateTopLevelInjectors() {
    messager.printMessage(Kind.NOTE, "generateTopLevelInjectors");
    SetMultimap<BindingKey, ClassName> keyToPackagedInjectorMap =
        utils.reverseSetMultimap(generatedBindingsForPackagedInjector);

    // messager.printMessage(Kind.NOTE,
    // "generatedBindingsForPackagedInjector: " + generatedBindingsForPackagedInjector);
    // messager.printMessage(Kind.NOTE, "keyToPackagedInjectorMap: " + keyToPackagedInjectorMap);

    for (CoreInjectorInfo coreInjectorInfo : orderedCoreinjectors) {
      TypeSpec.Builder injectorBuilder =
          TypeSpec.classBuilder(
                  getTopLevelInjectorName(
                      coreInjectorInfo, topLevelInjectorPrefix, topLevelInjectorSuffix))
              .addAnnotation(
                  AnnotationSpec.builder(Generated.class)
                      .addMember("value", "$S", GENERATOR_NAME)
                      .build())
              .addModifiers(Modifier.PUBLIC);

      // method simple name and type.
      Set<Pair<String, TypeName>> injectionMethodsDone = new HashSet<>();

      // Member injector interfaces.
      for (TypeElement injector : coreInjectorToComponentMap.get(coreInjectorInfo)) {
        injectorBuilder.addSuperinterface(TypeName.get(injector.asType()));
      }

      // Ctor
      MethodSpec.Builder ctorBuilder =
          MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

      ctorBuilder.addStatement(
          "$T.out.printf($S, $L)", ClassName.get(System.class), "This is tiger: %s\n", "this");
      // Ctor - Containing top level injector.
      // TODO: remove this .
      String containingInjectorName = "containingInjector";
      if (componentTree.get(coreInjectorInfo) != null) {
        ClassName containingInjectorClassName =
            ClassName.get(
                topLevelPackageString,
                getTopLevelInjectorName(
                    componentTree.get(coreInjectorInfo),
                    topLevelInjectorPrefix,
                    topLevelInjectorSuffix));
        injectorBuilder.addField(
            containingInjectorClassName, containingInjectorName, Modifier.PRIVATE);
        ctorBuilder
            .addParameter(containingInjectorClassName, containingInjectorName)
            .addStatement("this.$L = $L", containingInjectorName, containingInjectorName);
      }

      // Ctor - ancester top level injectors.
      CoreInjectorInfo tmp = coreInjectorInfo;
      while (componentTree.get(tmp) != null) {
        tmp = componentTree.get(tmp);
        ClassName className =
            ClassName.get(
                topLevelPackageString,
                getTopLevelInjectorName(tmp, topLevelInjectorPrefix, topLevelInjectorSuffix));
        String sourceCodeName = utils.getSourceCodeName(className);
        injectorBuilder.addField(className, sourceCodeName);
        if (tmp.equals(componentTree.get(coreInjectorInfo))) {
          ctorBuilder.addStatement("this.$L = $L", sourceCodeName, containingInjectorName);
        } else {
          ctorBuilder.addStatement(
              "this.$L = $L.$L", sourceCodeName, containingInjectorName, sourceCodeName);
        }
      }

      // Ctor - Component dependencies
      for (TypeElement dep :
          utils.sortByFullName(coreInjectorToComponentDependencyMap.get(coreInjectorInfo))) {
        if (utils.isEitherComponent(dep)) {
          continue;
        }
        ClassName className = ClassName.get(dep);
        String sourceCodeName = utils.getSourceCodeName(className);
        injectorBuilder.addField(className, sourceCodeName, Modifier.PUBLIC);
        ctorBuilder
            .addParameter(className, sourceCodeName)
            .addStatement("this.$L = $L", sourceCodeName, sourceCodeName);
      }

      // Ctor - @BindsInstance
      for (BindingKey key :
          utils.sortBindingKeys(coreInjectorToBindsInstanceMap.get(coreInjectorInfo))) {
        String sourceCodeName = utils.getSourceCodeNameHandlingBox(key, dependencies);
        injectorBuilder.addField(key.getTypeName(), sourceCodeName, Modifier.PUBLIC);
        ctorBuilder
            .addParameter(key.getTypeName(), sourceCodeName)
            .addStatement("this.$L = $L", sourceCodeName, sourceCodeName);
      }

      // Ctor - Passed modules.
      Set<TypeElement> allPassedModules = new HashSet<>();
      allPassedModules.addAll(nonNullaryCtorModules.get(coreInjectorInfo));
      allPassedModules.addAll(nonNullaryCtorUnscopedModules);
      for (TypeElement passedModule : utils.sortByFullName(allPassedModules)) {
        String moduleName = utils.getSourceCodeName(passedModule);
        ClassName moduleTypeName = (ClassName) TypeName.get(passedModule.asType());
        ctorBuilder
            .addParameter(moduleTypeName, moduleName)
            .addStatement("this.$N = $N", moduleName, moduleName);
        injectorBuilder
            .addField(moduleTypeName, moduleName, Modifier.PRIVATE)
            .addMethod(
                MethodSpec.methodBuilder(utils.getGetMethodName(moduleTypeName))
                    .addModifiers(Modifier.PUBLIC)
                    .returns(moduleTypeName)
                    .addStatement("return $N", moduleName)
                    .build());
      }

      injectorBuilder.addMethod(ctorBuilder.build());

      // Injection methods and non-injection methods.
      Set<String> miscMethodNames = new HashSet<>();
      Set<TypeElement> allMembersInjectors =
          Sets.newHashSet(coreInjectorToComponentMap.get(coreInjectorInfo));
      for (TypeElement injector : allMembersInjectors) {
        for (Element element : processingEnv.getElementUtils().getAllMembers(injector)) {
          messager.printMessage(Kind.NOTE, "method: " + element);
          if (!element.getKind().equals(ElementKind.METHOD)) {
            continue;
          }
          ExecutableElement method = (ExecutableElement) element;
          ExecutableType methodType =
              (ExecutableType)
                  processingEnv.getTypeUtils().asMemberOf((DeclaredType) injector.asType(), method);
          // Injection methods.
          if (utils.isInjectionMethod(element)) {

            // TODO: add duplicate check for provision method also.
            if (injectionMethodsDone.add(
                    Pair.of(
                        method.getSimpleName().toString(),
                        TypeName.get(Iterables.getOnlyElement(methodType.getParameterTypes()))))
                == false) {
              messager.printMessage(Kind.WARNING, "duplicate injection method: " + method);
              continue;
            }

            TypeMirror typeMirror = Iterables.getOnlyElement(methodType.getParameterTypes());
            TypeElement cls = (TypeElement) ((DeclaredType) typeMirror).asElement();
            messager.printMessage(
                Kind.NOTE, TAG + ".generateTopLevelInjector-injection method : " + methodType);

            ClassName packagedInjectorClassName =
                getPackagedInjectorNameOfScope(
                    utils.getPackageString(cls), coreInjectorInfo.getScope());
            injectorBuilder.addMethod(
                MethodSpec.methodBuilder(method.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassName.get(cls), "arg")
                    .addStatement(
                        "$L().inject(arg)", utils.getGetMethodName(packagedInjectorClassName))
                    .build());
          } else if (utils.isComponentProvisionMethod(element)) {
            messager.printMessage(Kind.ERROR, "Injecting components is not supported: " + element);
          } else if (utils.isSubcomponentProvisionMethod(element)) {
            generateGetSubcomponentMethod((ExecutableElement) element, injectorBuilder);
          } else if (utils.isProvisionMethodInInjector(element)) {
            /**
             * TODO: handle it in the way consistent with other {@link DependencySourceType} in
             * {@link #generateProvisionMethodIfNeeded(BindingKey, TypeElement)}.
             */
            // non-injection methods, provision methods or getSubComponent method in
            // editors. NOTE(freeman): subcomponent should be converted to component.

            if (!miscMethodNames.add(method.getSimpleName().toString())) {
              continue;
            }
            MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder(method.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.get(method.getReturnType()));
            BindingKey providedKey = utils.getKeyProvidedByMethod(method);
            ClassName packagedInjectorClassName = null;
            for (ClassName className : keyToPackagedInjectorMap.get(providedKey)) {
              if (isInjectorOfScope(className, coreInjectorInfo.getScope())) {
                packagedInjectorClassName = className;
                break;
              }
            }
            if (packagedInjectorClassName == null) {
              messager.printMessage(
                  Kind.WARNING,
                  String.format(
                      "PackagedInjector or multiBindingInjector not found for key: %s "
                          + "from provisionMethod: %s. Probably it is not used.",
                      providedKey, method));
              // Create a dumb method
              String statement = "return ";
              TypeKind typeKind = method.getReturnType().getKind();
              if (typeKind.equals(TypeKind.BOOLEAN)) {
                statement += "false";
              } else if (typeKind.equals(TypeKind.CHAR)) {
                statement += "\'0\'";
              } else if (typeKind.isPrimitive()) {
                statement += "0";
              } else {
                statement += "null";
              }
              methodBuilder.addStatement(statement);
            } else {
              String statement = "return $L().$L()";
              methodBuilder.addStatement(
                  statement,
                  utils.getGetMethodName(packagedInjectorClassName),
                  utils.getProvisionMethodName(dependencies, providedKey));
            }
            // messager.printMessage(Kind.NOTE, "provision method added: " + methodBuilder.build());
            injectorBuilder.addMethod(methodBuilder.build());
            // } else if (utils.isEitherComponentProvisionMethod(element)) {
            //   // TODO: support get component method.
            //   if(utils.isComponentProvisionMethod(element)) {
            //     throw new RuntimeException("component provision method is not suported yet.");
            //   }
            //   generateGetSubcomponentMethod((ExecutableElement) element, injectorBuilder);
            // } else if (utils.isEitherComponentBuilderProvisionMethod(element)) {
            //   /**
            //    * TODO: handle it in the way consistent with other {@link DependencySourceType} in
            //    * {@link #generateProvisionMethodIfNeeded(BindingKey, TypeElement)}
            //    */
            //   generateExplicitProvisionMethodForEitherComponentBuilder(
            //       (ExecutableElement) element, injectorBuilder);
          } else if (isIrrelevantMethodInInjector(element)) {
            // do nothing
          } else {
            messager.printMessage(
                Kind.WARNING,
                String.format("Element %s ignored from injector %s.", element, injector));
          }
        }
      }

      // Methods to get packaged injectors.
      for (Map.Entry<ClassName, TypeSpec.Builder> entry : packagedInjectorBuilders.entrySet()) {
        ClassName injectorClassName = entry.getKey();
        if (!coreInjectorInfo.equals(
            getComponentFromPackagedInjectorClassName(injectorClassName))) {
          continue;
        }
        String packagedInjectorSourceCodeName = utils.getSourceCodeName(injectorClassName);
        injectorBuilder.addField(
            injectorClassName, packagedInjectorSourceCodeName, Modifier.PRIVATE);
        MethodSpec.Builder methodSpecBuilder =
            MethodSpec.methodBuilder(utils.getGetMethodName(injectorClassName))
                .addModifiers(Modifier.PUBLIC)
                .returns(injectorClassName)
                .addStatement("$T result = $N", injectorClassName, packagedInjectorSourceCodeName)
                .beginControlFlow("if (result == null)");

        StringBuilder stringBuilder = new StringBuilder("result = $N = new $T(this");
        if (componentTree.get(coreInjectorInfo) != null) {
          ClassName containingPackageInjectorClassName =
              getInjectorNameOfScope(
                  injectorClassName, componentTree.get(coreInjectorInfo).getScope());
          stringBuilder
              .append(", ")
              .append(containingInjectorName)
              .append(".")
              .append(utils.getGetMethodName(containingPackageInjectorClassName))
              .append("()");
        }
        stringBuilder.append(")");
        methodSpecBuilder.addStatement(
            stringBuilder.toString(), packagedInjectorSourceCodeName, injectorClassName);

        methodSpecBuilder.endControlFlow().addStatement("return result");
        injectorBuilder.addMethod(methodSpecBuilder.build());
      }

      // Builder and builder().
      generateInjectorBuilder(coreInjectorInfo, injectorBuilder);

      ClassName builderClassName = getTopLevelInjectorBuilderClassName(coreInjectorInfo);
      MethodSpec.Builder methodSpecBuilder =
          // "Dagger compatible component will have "builder()", we need a different name.
          MethodSpec.methodBuilder("getBuilder")
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
              .returns(builderClassName)
              .addStatement("return new $T()", builderClassName);
      injectorBuilder.addMethod(methodSpecBuilder.build());

      // provision methods for (sub)component builders that can be provided by this core injector.
      for (TypeElement b : coreInjectorToBothBuilderMap.get(coreInjectorInfo)) {
        generateImplicitProvisionMethodForEitherComponentBuilderInTopLevelInjector(
            injectorBuilder, b);
      }

      // Write
      JavaFile javaFile = JavaFile.builder(topLevelPackageString, injectorBuilder.build()).build();
      try {
        javaFile.writeTo(processingEnv.getFiler());
      } catch (IOException e) {
        messager.printMessage(Kind.ERROR, e.toString());
      }
    }
  }

  // Returns if the element is a method irrelevant to injection.
  private boolean isIrrelevantMethodInInjector(Element element) {
    return !utils.isMethod(element) || !element.getModifiers().contains(Modifier.ABSTRACT);
  }

  private boolean isEitherComponentBuilderProvsionMethodProvidedByModule(Element method) {
    TypeElement element = utils.getReturnTypeElement((ExecutableElement) method);
    return utils.isEitherComponentBuilderProvisionMethod(method)
        && Iterables.getOnlyElement(utils.getDependencyInfo(dependencies, BindingKey.get(element)))
            .getDependencySourceType()
            .equals(DependencySourceType.MODULE);
  }

  /**
   * (sub)component builder provision method that has not been explicitly specific in the parent
   * (sub)component.
   */
  private void generateImplicitProvisionMethodForEitherComponentBuilderInTopLevelInjector(
      Builder injectorBuilder, TypeElement componentBuilder) {
    Preconditions.checkArgument(utils.isEitherComponentBuilder(componentBuilder));
    TypeElement enclosingElement = (TypeElement) componentBuilder.getEnclosingElement();
    boolean isSubcomponent = utils.isSubcomponent(enclosingElement);
    ClassName builderImplementationName = getClassNameForEitherComponentBuilder(componentBuilder);
    MethodSpec.Builder methodBuilder =
        MethodSpec.methodBuilder(utils.getGetMethodName(componentBuilder))
            .addModifiers(Modifier.PUBLIC)
            .returns(builderImplementationName);

    methodBuilder.addCode(
        "return new $T($L);", builderImplementationName, isSubcomponent ? "this" : "");
    injectorBuilder.addMethod(methodBuilder.build());
  }

  private ClassName getClassNameForEitherComponentBuilder(TypeElement componentBuilder) {
    TypeElement enclosingElement = (TypeElement) componentBuilder.getEnclosingElement();

    return ClassName.get(
        utils.getPackageString(enclosingElement),
        utils.getComponentImplementationSimpleNameFromInterface(enclosingElement),
        componentBuilder.getSimpleName().toString());
  }

  private void generateGetSubcomponentMethod(ExecutableElement method, Builder injectorBuilder) {
    TypeElement returnType = utils.getReturnTypeElement(method);
    messager.printMessage(
        Kind.NOTE,
        TAG + ".generateGetSubcomponentMethod returnType: " + returnType + " method: " + method);

    TypeElement scope =
        (TypeElement) utils.getScopeType(returnType, scopeAliasCondenser).asElement();
    CoreInjectorInfo coreInjectorInfo = new CoreInjectorInfo(scope);

    Set<TypeElement> allNonNullaryCtorModules = new HashSet<>();
    allNonNullaryCtorModules.addAll(nonNullaryCtorModules.get(coreInjectorInfo));
    allNonNullaryCtorModules.addAll(nonNullaryCtorUnscopedModules);

    // Method head
    MethodSpec.Builder buildMethodBuilder =
        MethodSpec.methodBuilder(method.getSimpleName().toString())
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(TypeName.get(returnType.asType()));

    Map<BindingKey, String> keyNameMap = new HashMap<>();
    for (VariableElement parameter : method.getParameters()) {
      keyNameMap.put(
          utils.getBindingKeyForMethodParameter(parameter), parameter.getSimpleName().toString());
    }

    // method parameters
    for (VariableElement parameter : method.getParameters()) {
      buildMethodBuilder.addParameter(
          TypeName.get(parameter.asType()), parameter.getSimpleName().toString());
    }

    // return statement
    StringBuilder returnCodeBuilder = new StringBuilder("return new $T(this, ");

    // return statement deps
    for (TypeElement dep :
        utils.sortByFullName(coreInjectorToComponentDependencyMap.get(coreInjectorInfo))) {
      BindingKey key = BindingKey.get(dep);
      String name = keyNameMap.containsKey(key) ? keyNameMap.get(key) : "null";
      returnCodeBuilder.append(name).append(", ");
    }

    // return statement @BindsInstance
    for (BindingKey key :
        utils.sortBindingKeys(coreInjectorToBindsInstanceMap.get(coreInjectorInfo))) {
      String name = keyNameMap.containsKey(key) ? keyNameMap.get(key) : "null";
      returnCodeBuilder.append(name).append(", ");
    }

    // return statement modules
    for (TypeElement module : utils.sortByFullName(allNonNullaryCtorModules)) {
      BindingKey key = BindingKey.get(module);
      String moduleName = keyNameMap.containsKey(key) ? keyNameMap.get(key) : "null";
      returnCodeBuilder.append(moduleName).append(", ");
    }
    int size = returnCodeBuilder.length();
    returnCodeBuilder.delete(size - 2, size);
    returnCodeBuilder.append(");");
    buildMethodBuilder.addCode(
        returnCodeBuilder.toString(),
        ClassName.get(
            topLevelPackageString,
            getTopLevelInjectorName(
                coreInjectorInfo, topLevelInjectorPrefix, topLevelInjectorSuffix)));

    injectorBuilder.addMethod(buildMethodBuilder.build());
  }

  private void generateExplicitProvisionMethodForEitherComponentBuilder(
      ExecutableElement method, Builder injectorBuilder) {
    TypeElement returnType = utils.getReturnTypeElement(method);
    messager.printMessage(Kind.NOTE, "generateExplicitProvisionMethodForEitherComponentBuilder");
    messager.printMessage(Kind.NOTE, "returnType: " + returnType + " method: " + method);

    Preconditions.checkArgument(
        utils.getQualifier(returnType) == null,
        "Qualifier found for (sub)component builder: " + returnType);

    MethodSpec.Builder buildMethodBuilder =
        MethodSpec.methodBuilder(method.getSimpleName().toString())
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(TypeName.get(returnType.asType()));

    buildMethodBuilder.addCode("return $L();", utils.getGetMethodName(returnType));

    injectorBuilder.addMethod(buildMethodBuilder.build());
  }

  ClassName getTopLevelInjectorBuilderClassName(CoreInjectorInfo component) {
    return ClassName.get(
        topLevelPackageString,
        getTopLevelInjectorName(component, topLevelInjectorPrefix, topLevelInjectorSuffix),
        "Builder");
  }

  private boolean isInjectorOfScope(ClassName injectorClassName, TypeElement scope) {
    return injectorClassName
        .simpleName()
        .contains(scope.getQualifiedName().toString().replace(".", "_"));
  }

  private void generateInjectorBuilder(
      CoreInjectorInfo coreInjectorInfo, TypeSpec.Builder injectorBuilder) {
    TypeSpec.Builder builderBuilder =
        TypeSpec.classBuilder("Builder").addModifiers(Modifier.PUBLIC, Modifier.STATIC);

    ClassName injectorClassName =
        ClassName.get(
            topLevelPackageString,
            getTopLevelInjectorName(
                coreInjectorInfo, topLevelInjectorPrefix, topLevelInjectorSuffix));
    boolean hasParent = componentTree.get(coreInjectorInfo) != null;
    ClassName parentClassName = null;

    // set parent inject methods.
    if (hasParent) {
      parentClassName =
          ClassName.get(
              topLevelPackageString,
              getTopLevelInjectorName(
                  componentTree.get(coreInjectorInfo),
                  topLevelInjectorPrefix,
                  topLevelInjectorSuffix));
      utils.addSetMethod(
          types, elements, injectorClassName,
          builderBuilder,
          parentClassName,
          getParentComponentSetterName(componentTree.get(coreInjectorInfo)));
    }

    for (TypeElement dep : coreInjectorToComponentDependencyMap.get(coreInjectorInfo)) {
      utils.addSetMethod(
          types, elements, injectorClassName, builderBuilder, (ClassName) ClassName.get(dep.asType()));
    }

    for (BindingKey key : coreInjectorToBindsInstanceMap.get(coreInjectorInfo)) {
      utils.addSetMethod(
          types, elements, injectorClassName,
          builderBuilder,
          key.getTypeName(),
          utils.getSourceCodeNameHandlingBox(key, dependencies));
    }

    /**
     * Set module methods. Theoretically, is a passed module is needed by a injector should be
     * decided by whether it is needed. Here is just a simpler way. All scoped modules go to
     * injector of the same scope. All unscoped modules go to every injector. It is not possible to
     * have modules of different scope in a injector. This way the modules created is a superset of
     * what are required. But that's fine because null can be passed in for modules not needed. This
     * is even unnoticeable when using builder to create injector. The null module is just never
     * referenced. Otherwise it is a bug, sadly it is not caught until runtime. This makes it easier
     * to create wrapper component defined dagger around core injectors. Anyway, passed module is
     * not a good idea and could/should be removed.
     */
    List<TypeElement> allNonNullaryCtorModules = new ArrayList<>();
    allNonNullaryCtorModules.addAll(nonNullaryCtorModules.get(coreInjectorInfo));
    allNonNullaryCtorModules.addAll(nonNullaryCtorUnscopedModules);
    for (TypeElement m : allNonNullaryCtorModules) {
      utils.addSetMethod(types, elements, injectorClassName, builderBuilder, (ClassName) ClassName.get(m.asType()));
    }

    // build() method.
    MethodSpec.Builder buildMethodBuilder =
        MethodSpec.methodBuilder("build").addModifiers(Modifier.PUBLIC).returns(injectorClassName);
    StringBuilder returnCodeBuilder = new StringBuilder("return new $T(");
    boolean needLeadingComma = false;
    if (hasParent) {
      needLeadingComma = true;
      String parentInjectorFieldName = utils.getSourceCodeName(parentClassName);
      returnCodeBuilder.append(parentInjectorFieldName);
    }

    Set<TypeElement> deps = coreInjectorToComponentDependencyMap.get(coreInjectorInfo);
    if (!deps.isEmpty()) {
      if (needLeadingComma) {
        returnCodeBuilder.append(", ");
      }
      needLeadingComma = true;
      for (TypeElement dep : utils.sortByFullName(deps)) {
        String parameterName = utils.getSourceCodeName(TypeName.get(dep.asType()));
        returnCodeBuilder.append(parameterName).append(", ");
      }
      int size = returnCodeBuilder.length();
      returnCodeBuilder.delete(size - 2, size);
    }

    Set<BindingKey> keys = coreInjectorToBindsInstanceMap.get(coreInjectorInfo);
    if (!keys.isEmpty()) {
      if (needLeadingComma) {
        returnCodeBuilder.append(", ");
      }
      needLeadingComma = true;
      for (BindingKey key : utils.sortBindingKeys(keys)) {
        String parameterName = utils.getSourceCodeNameHandlingBox(key, dependencies);
        returnCodeBuilder.append(parameterName).append(", ");
      }
      int size = returnCodeBuilder.length();
      returnCodeBuilder.delete(size - 2, size);
    }

    if (!allNonNullaryCtorModules.isEmpty()) {
      if (needLeadingComma) {
        returnCodeBuilder.append(", ");
      }
      needLeadingComma = true;
      for (TypeElement module : utils.sortByFullName(allNonNullaryCtorModules)) {
        String parameterName = utils.getSourceCodeName(TypeName.get(module.asType()));
        returnCodeBuilder.append(parameterName).append(", ");
      }
      int size = returnCodeBuilder.length();
      returnCodeBuilder.delete(size - 2, size);
    }
    returnCodeBuilder.append(");");
    buildMethodBuilder.addCode(returnCodeBuilder.toString(), injectorClassName);
    builderBuilder.addMethod(buildMethodBuilder.build());

    injectorBuilder.addType(builderBuilder.build());
  }

  public String getTopLevelInjectorName(
      CoreInjectorInfo component, String topLevelInjectorPrefix, String topLevelInjectorSuffix) {
    return this.topLevelInjectorPrefix + component.getName() + this.topLevelInjectorSuffix;
  }

  // Setter does not include prefix "Dagger" to make it compatible with dagger.
  // And lower the first letter.
  public String getParentComponentSetterName(CoreInjectorInfo component) {
    String result = component.getName() + topLevelInjectorSuffix;
    result = result.substring(0, 1).toLowerCase() + result.substring(1);
    return result;
  }
}
