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
import com.squareup.javapoet.TypeVariableName;

import dagger.Lazy;
import dagger.MapKey;
import dagger.Provides.Type;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Generated;
import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.inject.Named;
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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic.Kind;

/**
 * Generates packaged injectors, multi-binding injectors and top level injectors. The former is to
 * get around accessibility limitation. The second is unscoped injector dedicated for
 * multi-bindings. There is only one class for each scope, though could/will be multiple instances.
 * Top level injectors orchestrate everything.
 *
 * PackagedInjector for a {@link NewBindingKey} is decided this way.
 * <ul>
 * <li>Provided Set(potentially all multi) binding: unscoped dedicated global multi-binding
 * injector. All multi-bindings are unscoped. If scoped, ignored(or disallowed at all). For each
 * binding of a multi-binding, they will have a non-null dependencyInfo from which the packaged
 * injector can be deduced. Contributors of multi-bindings providing non-set binding and handled
 * normally as below.
 * <li>Built-in binding, i.e., some_qualifier Provider<Foo> or Lazy<Foo>: binding provider package of
 * some_qualifier Foo.
 * <li>Provided non-set binding: binding provider package.
 * <li>Other(MUST be resolved by generic binding): go to referencing package. if Referencing class
 * is generic, then continue. This way we could run into to access problem if the referenced generic
 * class is not public. But better chance is that the type variable is not public but accessible by
 * the original non-generic class. Let's see how it works. This therefore could be created in
 * multiple PackagedInjectors. It MUST be unscoped.
 * 
 * Generated injectors are not thread safe for performance reason. Callers must guarantee thread
 * safe if needed.
 * </ul>
 */
class NewInjectorGenerator {
  private static String TAG = "NewInjectorGenerator";

  /**
   * Used for value of @Generated(). It starts with "dagger." so that it will be exempted from
   * strict java deps check. TODO(freeman): change it to tiger.
   */
  private static final String GENERATOR_NAME = "dagger.NewInjectorGenerator";

  static final String PACKAGED_INJECTOR_NAME = "PackagedInjector";
  static final String MULTI_BINDING_INJECTOR_NAME = "MultiBindingInjector";
  private static final String TOP_LEVEL_INJECTOR_FIELD = "topLevelInjector";
  // Refers the packaged injector for parent scope.
  private static final String CONTAINING_PACKAGED_INJECTOR_FIELD = "containingPackagedInjector";
  private static final String UNSCOPED_SUFFIX = "_unscoped";

  private final SetMultimap<NewBindingKey, NewDependencyInfo> dependencies;
  private final Set<NewBindingKey> explicitScopes;
  private final NewScopeCalculator scopeCalculator;
  private SetMultimap<ComponentInfo, TypeElement> nonNullaryCtorModules = HashMultimap.create();
  private Set<TypeElement> nonNullaryCtorUnscopedModules = new HashSet<>();
  private final SetMultimap<ComponentInfo, TypeElement> memberInjectors;
  // Mapping from child to parent.
  private final Map<ComponentInfo, ComponentInfo> componentTree;
  private final List<ComponentInfo> orderedComponents;
  private final String topLevelPackageString;
  private final List<String> errors = new ArrayList<>();

  // Includes multi-binding package.
  private final SetMultimap<ClassName, NewBindingKey> generatedBindingsForPackagedInjector =
      HashMultimap.create();
  // Includes dagger modules with getFooModule() generated for the packaged
  // injectors, i.e., <PackagedInjector, <Module, MethodSpec>>.
  private Map<ClassName, Map<ClassName, MethodSpec>> modulesWithGetter = Maps.newHashMap();

  // From packaged injector to spec builder.
  private final Map<ClassName, TypeSpec.Builder> packagedInjectorBuilders = Maps.newHashMap();

  // From packaged injector to injected ClassName.
  private final SetMultimap<ClassName, ClassName> injectedClassNamesForPackagedInjector =
      HashMultimap.create();

  private final ProcessingEnvironment processingEnv;
  private final Messager messager;

  private final String topLevelInjectorPrefix;
  private final String topLevelInjectorSuffix;

  public NewInjectorGenerator(SetMultimap<NewBindingKey, NewDependencyInfo> dependencies,
      NewScopeCalculator scopeCalculator, SetMultimap<ComponentInfo, TypeElement> modules,
      Set<TypeElement> unscopedModules, SetMultimap<ComponentInfo, TypeElement> memberInjectors,
      Map<ComponentInfo, ComponentInfo> componentTree, ComponentInfo rootComponent,
      String topPackageString, String topLevelInjectorPrefix, String topLevelInjectorSuffix,
      ProcessingEnvironment env) {
    this.dependencies = dependencies;
    // Utilities.printDependencies(dependencies);
    this.scopeCalculator = scopeCalculator;
    this.explicitScopes = scopeCalculator.getExplicitScopedKeys();
    this.memberInjectors = LinkedHashMultimap.create(memberInjectors);
    this.componentTree = componentTree;
    this.topLevelPackageString = topPackageString;
    this.topLevelInjectorPrefix = topLevelInjectorPrefix;
    this.topLevelInjectorSuffix = topLevelInjectorSuffix;
    this.processingEnv = env;
    this.messager = env.getMessager();
    if (componentTree.isEmpty()) {
      this.orderedComponents = Lists.newArrayList();
      this.orderedComponents.add(rootComponent);
    } else {
      Preconditions.checkArgument(rootComponent == null);
      this.orderedComponents = Utils.getOrderedScopes(componentTree);
    }
    for (ComponentInfo componentInfo : orderedComponents) {
      nonNullaryCtorModules.putAll(componentInfo,
          Utils.getNonNullaryCtorOnes(modules.get(componentInfo)));
    }
    nonNullaryCtorUnscopedModules.addAll(Utils.getNonNullaryCtorOnes(unscopedModules));
  }

  /**
   * Generates PackagedInjectors and return the generated.
   */
  public void generate() {
    messager.printMessage(Kind.NOTE,
        String.format("%s.generate() for %s", TAG, topLevelPackageString));
    generateAll();

    if (!errors.isEmpty()) {
      messager.printMessage(Kind.ERROR, "Generating injectors failed: ");
      for (String s : errors) {
        messager.printMessage(Kind.ERROR, s);
      }
    }
  }

  /**
   * Get {@link TypeSpec} for packaged injector specified by className.
   */
  private TypeSpec.Builder getInjectorTypeSpecBuilder(ClassName injectorClassName) {
    if (!packagedInjectorBuilders.containsKey(injectorClassName)) {
      // Generate packaged injectors for all components. Containing is
      // needed so that the injector chain will not break.
      // Contained is needed to provide access of its containing packaged
      // injector for peer packaged injectors.
      for (ComponentInfo component : orderedComponents) {
        TypeSpec.Builder typeSpecBuilder = createInjectorTypeSpec(component, injectorClassName);
        packagedInjectorBuilders
            .put(getInjectorNameOfScope(injectorClassName, component.getScope()), typeSpecBuilder);
      }
    }
    return packagedInjectorBuilders.get(injectorClassName);
  }

  private ComponentInfo getComponentFromPackagedInjectorClassName(
      ClassName packagedInjectorClassName) {
    for (ComponentInfo component : orderedComponents) {
      if (packagedInjectorClassName.simpleName()
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

  private TypeSpec.Builder createInjectorTypeSpec(ComponentInfo component,
      ClassName injectorClassName) {
    ClassName cn = getInjectorNameOfScope(injectorClassName, component.getScope());
    // System.out.println("createPackagedInjectorTypeSpec. name: " +
    // Utilities.getClassCanonicalName(cn));
    TypeSpec.Builder typeSpecBuilder = TypeSpec.classBuilder(cn.simpleName())
        .addModifiers(Modifier.PUBLIC).addAnnotation(AnnotationSpec.builder(Generated.class)
            .addMember("value", "$S", GENERATOR_NAME).build());

    // Top level injector.
    ClassName topLevelInjectorClassName =
        ClassName.get(topLevelPackageString, getTopLevelInjectorName(component));
    typeSpecBuilder.addField(topLevelInjectorClassName, TOP_LEVEL_INJECTOR_FIELD, Modifier.PRIVATE);

    MethodSpec.Builder ctorSpec = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
        .addParameter(topLevelInjectorClassName, TOP_LEVEL_INJECTOR_FIELD);
    ctorSpec.addStatement("this.$N = $N", TOP_LEVEL_INJECTOR_FIELD, TOP_LEVEL_INJECTOR_FIELD);

    // Containing packaged injector.
    if (componentTree.get(component) != null) {
      ClassName containingInjectorClassName =
          getInjectorNameOfScope(injectorClassName, componentTree.get(component).getScope());
      typeSpecBuilder.addField(containingInjectorClassName, CONTAINING_PACKAGED_INJECTOR_FIELD,
          Modifier.PRIVATE);
      ctorSpec.addParameter(containingInjectorClassName, CONTAINING_PACKAGED_INJECTOR_FIELD);
      ctorSpec.addStatement("this.$N = $N", CONTAINING_PACKAGED_INJECTOR_FIELD,
          CONTAINING_PACKAGED_INJECTOR_FIELD);
    }

    typeSpecBuilder.addMethod(ctorSpec.build());

    return typeSpecBuilder;
  }

  private void generateAll() {
    for (ComponentInfo component : orderedComponents) {
      Set<ClassName> injected = new HashSet<>();
      Set<TypeElement> allMembersInjectors = Sets.newHashSet(memberInjectors.get(component));
      while (!allMembersInjectors.isEmpty()) {
        TypeElement injector = Iterables.getFirst(allMembersInjectors, null);
        Preconditions.checkNotNull(injector, String.format("Empty allMembersInjector."));
        allMembersInjectors.remove(injector);
        for (TypeMirror parentInterface : injector.getInterfaces()) {
          allMembersInjectors.add((TypeElement) ((DeclaredType) parentInterface).asElement());
        }
        for (Element method : injector.getEnclosedElements()) {
          if (Utils.isInjectionMethod(method)) {
            VariableElement var =
                Iterables.getOnlyElement(((ExecutableElement) method).getParameters());
            if (var.asType().getKind().equals(TypeKind.TYPEVAR)) {
              // TODO(freeman): support generic injection method.
              continue;
            }
            TypeElement varTypeElement = (TypeElement) ((DeclaredType) var.asType()).asElement();
            if (!injected.add(ClassName.get(varTypeElement))) {
              continue;
            }
            generateInjectionMethod(varTypeElement, component.getScope());
          } else if (Utils.isProvisionMethodInInjector(method)) {
            generateProvisionMethodIfNeeded(
                Utils.getKeyProvidedByMethod((ExecutableElement) method), injector);
          } else {
            messager.printMessage(Kind.WARNING,
                String.format("Unknown element %s from injector %s.", method, injector));
          }
        }
      }
    }

    for (ComponentInfo component : orderedComponents) {
      if (componentTree.get(component) == null) {
        continue;
      }
      for (Map.Entry<ClassName, TypeSpec.Builder> entry : packagedInjectorBuilders.entrySet()) {
        ClassName packagedInjectorClassName = entry.getKey();
        if (!component
            .equals(getComponentFromPackagedInjectorClassName(packagedInjectorClassName))) {
          continue;
        }
        generateInheritedProvisionMethods(packagedInjectorClassName);
      }
    }

    for (ComponentInfo component : orderedComponents) {
      if (componentTree.get(component) == null) {
        continue;
      }
      for (Map.Entry<ClassName, TypeSpec.Builder> entry : packagedInjectorBuilders.entrySet()) {
        ClassName packagedInjectorClassName = entry.getKey();
        if (!component
            .equals(getComponentFromPackagedInjectorClassName(packagedInjectorClassName))) {
          continue;
        }
        generateInheritedInjectionMethods(packagedInjectorClassName);
      }
    }

    for (Map.Entry<ClassName, TypeSpec.Builder> entry : packagedInjectorBuilders.entrySet()) {
      String packageString = entry.getKey().packageName();
      TypeSpec.Builder builder = entry.getValue();
      JavaFile javaFile = JavaFile.builder(packageString, builder.build()).build();

//       messager.printMessage(Kind.NOTE, String.format("java file: %s", javaFile));
      try {
        javaFile.writeTo(processingEnv.getFiler());
      } catch (IOException e) {
        Throwables.propagate(e);
      }
    }

    generateTopLevelInjectors();
  }

  private void generateInheritedProvisionMethods(ClassName packagedInjectorClassName) {
    ComponentInfo component = getComponentFromPackagedInjectorClassName(packagedInjectorClassName);
    Preconditions.checkArgument(componentTree.get(component) != null, String
        .format("No inherited provision methods to generate for %s", packagedInjectorClassName));

    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    ClassName containingPackagedInjectorClassName =
        getInjectorNameOfScope(packagedInjectorClassName, componentTree.get(component).getScope());
    for (NewBindingKey key : generatedBindingsForPackagedInjector
        .get(containingPackagedInjectorClassName)) {
      String provisionMethodName = getProvisionMethodName(key);
      componentSpecBuilder.addMethod(MethodSpec.methodBuilder(provisionMethodName)
          .addModifiers(Modifier.PUBLIC).returns(key.getTypeName())
          .addStatement("return $L.$L()", CONTAINING_PACKAGED_INJECTOR_FIELD, provisionMethodName)
          .build());
      Preconditions.checkState(
          generatedBindingsForPackagedInjector.put(packagedInjectorClassName, key),
          String.format("Injector %s already provides %s.", packagedInjectorClassName, key));
    }
  }

  private void generateInheritedInjectionMethods(ClassName packagedInjectorClassName) {
    ComponentInfo component = getComponentFromPackagedInjectorClassName(packagedInjectorClassName);
    if (componentTree.get(component) == null) {
      return;
    }
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    ClassName containingPackagedInjectorClassName =
        getInjectorNameOfScope(packagedInjectorClassName, componentTree.get(component).getScope());
    for (ClassName injectedClassName : injectedClassNamesForPackagedInjector
        .get(containingPackagedInjectorClassName)) {
      componentSpecBuilder.addMethod(MethodSpec.methodBuilder("inject")
          .addModifiers(Modifier.PUBLIC).addParameter(injectedClassName, "arg")
          .addStatement("$L.inject(arg)", CONTAINING_PACKAGED_INJECTOR_FIELD).build());
      injectedClassNamesForPackagedInjector.put(packagedInjectorClassName, injectedClassName);
    }
  }

  SetMultimap<NewBindingKey, ClassName> bingingKeyToPackagedInjectorMap = HashMultimap.create();

  /**
   * For all bindings but single contributor of a multi-binding, which is handled by
   * {@link #getPackagedInjectorForNewDependencyInfo(NewBindingKey, NewDependencyInfo)} . For
   * generic binding, package of referencing class has access to both raw type and parameter types,
   * though the provision method generated for it will be duplicated in each such package.
   */
  private ClassName getInjectorFor(NewBindingKey key, TypeElement referencingClass) {
    ClassName result = null;
    TypeElement scope = scopeCalculator.calculate(key);
    NewDependencyInfo dependencyInfo =
        Iterables.getFirst(Utils.getDependencyInfo(dependencies, key), null);
    // System.out.println("getInjectorFor: " + key + " dependencyInfo: "
    // + dependencyInfo);

    String packageString = null;
    boolean isMultiBinding = false;
    if (dependencyInfo != null) {
      isMultiBinding = dependencyInfo.isMultiBinding();
      packageString =
          Utils.getPackage(dependencyInfo.getSourceClassElement()).getQualifiedName().toString();
    } else if (Utils.hasBuiltinBinding(key)) {
      result = getInjectorFor(Utils.getElementKeyForBuiltinBinding(key), referencingClass);
    } else {
      NewDependencyInfo genericNewDependencyInfo =
          Utils.getDependencyInfoByGeneric(dependencies, key);
      if (genericNewDependencyInfo != null) {
        packageString = Utils.getPackageString(referencingClass);
      } else {
        errors.add(String.format("Cannot resolve %s.", key));
        throw new RuntimeException(errors.toString());
      }
    }

    if (result == null) {
      String simpleName = isMultiBinding ? Utils.getMultiBindingInjectorSimpleName(scope)
          : Utils.getPackagedInjectorSimpleName(scope);
      result = ClassName.get(isMultiBinding ? topLevelPackageString : packageString, simpleName);
    }

    return result;
  }

  /**
   * From the given dependencyInfo contributes to given key.
   */
  private ClassName getPackagedInjectorForNewDependencyInfo(NewBindingKey key,
      NewDependencyInfo dependencyInfo) {
    TypeElement scope = scopeCalculator.calculate(key);
    return getPackagedInjectorForNewDependencyInfo(scope, dependencyInfo);
  }

  /**
   * From the given dependencyInfo contributes to given key.
   */
  private ClassName getPackagedInjectorForNewDependencyInfo(TypeElement scope,
      NewDependencyInfo dependencyInfo) {
    return ClassName.get(Utils.getPackageString(dependencyInfo.getSourceClassElement()),
        Utils.getPackagedInjectorSimpleName(scope));
  }

  private ClassName getInjectorOfScope(NewBindingKey key, TypeElement referencingClass,
      TypeElement scope) {
    ClassName injectorClassName = getInjectorFor(key, referencingClass);
    return getInjectorNameOfScope(injectorClassName, scope);
  }

  private ClassName getPackagedInjectorNameOfScope(String packageString, TypeElement scope) {
    return ClassName.get(packageString, Utils.getPackagedInjectorSimpleName(scope));
  }

  private void generateProvisionMethodIfNeeded(final NewBindingKey key,
      TypeElement referencingClass) {
    ClassName packagedInjectorClassName = getInjectorFor(key, referencingClass);
    Builder injectorSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    if (!generatedBindingsForPackagedInjector.put(packagedInjectorClassName, key)) {
      return;
    }
    NewDependencyInfo dependencyInfo = Iterables.getFirst(dependencies.get(key), null);
    // System.out.println("generateProvisionMethodIfNeeded, key: " + key);
    // System.out.println("generateProvisionMethodIfNeeded, NewDependencyInfo: " +
    // dependencyInfo);
    // System.out.println("generateProvisionMethodIfNeeded, scope: " +
    // scopeCalculator.calculate(key));
    boolean scoped = explicitScopes.contains(key);
    String suffix = scoped ? UNSCOPED_SUFFIX : "";
    if (dependencyInfo != null) {
      if (dependencyInfo.isSet()) {
        // TODO(freeman): support binding for Set of Provider or Lazy.
        generateProvisionMethodForSet(key, referencingClass, suffix);
      } else {
        if (dependencyInfo.getProvisionMethodElement() == null) {
          generateProvisionMethodFromClass(key, referencingClass, suffix);
        } else {
          generateUniqueTypeProvisionMethodFromModule(key, suffix);
        }
      }
    } else if (Utils.hasBuiltinBinding(key)) {
      generateProvisionMethodForProviderOrLazy(key, referencingClass, suffix);
    } else if (Utils.isMap(key)) {
      generateProvisionMethodForMap(key, referencingClass, suffix);
    } else {
      NewDependencyInfo genericDependencyInfo = Utils.getDependencyInfoByGeneric(dependencies, key);
      if (genericDependencyInfo != null) {
        if (genericDependencyInfo.getProvisionMethodElement() == null) {
          generateProvisionMethodFromClass(key, referencingClass, suffix);
        } else {
          errors.add(String.format("Generic provision method not supported yet: %s -> %s", key,
              genericDependencyInfo));
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

  private void generateUniqueTypeProvisionMethodFromModule(NewBindingKey key, String suffix) {
    NewDependencyInfo dependencyInfo = Iterables.getOnlyElement(dependencies.get(key));

    Preconditions.checkNotNull(dependencyInfo.getProvisionMethodElement());
    TypeMirror returnType = dependencyInfo.getProvisionMethodElement().getReturnType();
    ClassName injectorClassName = getPackagedInjectorForNewDependencyInfo(key, dependencyInfo);
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(injectorClassName);
    NewBindingKey returnKey = NewBindingKey.get(returnType, key.getQualifier());
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(returnKey) + suffix);
    methodSpecBuilder.addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
        .returns(TypeName.get(returnType));

    methodSpecBuilder.addStatement("$T result", returnType);
    addNewStatementToMethodSpec(key, dependencyInfo, injectorClassName, methodSpecBuilder,
        "result");
    methodSpecBuilder.addStatement("return result");
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
    // messager.printMessage(Kind.NOTE, String.format(
    // "generateUniqueTypeProvisionMethodFromModule: \n key: %s, \n injector: %s, \n method: %s.",
    // key, injectorClassName, methodSpecBuilder.build()));
  }

  // If dependencyInfo is not null, then it is a contributor to set binding.
  private void generateSetTypeProvisionMethodForPackage(NewBindingKey key,
      Set<NewDependencyInfo> dependencyInfos, String suffix) {
    Preconditions.checkArgument(!dependencyInfos.isEmpty(),
        String.format("Empty dependencyInfo for key: %s", key));
    NewDependencyInfo dependencyInfo = Iterables.getFirst(dependencyInfos, null);
    TypeName returnType = key.getTypeName();
    ClassName injectorClassName = getPackagedInjectorForNewDependencyInfo(key, dependencyInfo);
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(injectorClassName);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix);
    methodSpecBuilder.addModifiers(Modifier.PUBLIC).returns(returnType);

    methodSpecBuilder.addStatement("$T result = new $T<>()", returnType, HashSet.class);
    for (NewDependencyInfo di : dependencyInfos) {
      methodSpecBuilder.beginControlFlow("");
      Preconditions.checkNotNull(di.getProvisionMethodElement());
      TypeName contributorType = TypeName.get(di.getProvisionMethodElement().getReturnType());
      methodSpecBuilder.addStatement("$T contributor", contributorType);
      addNewStatementToMethodSpec(key, di, injectorClassName, methodSpecBuilder, "contributor");
      if (di.getType().equals(Type.SET)) {
        methodSpecBuilder.addStatement("result.add(contributor)");
      } else {
        Preconditions.checkState(di.getType().equals(Type.SET_VALUES));
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

  // If dependencyInfo is not null, then it is a contributor to map biding.
  private void generateMapTypeProvisionMethodForPackage(final NewBindingKey key,
      Set<NewDependencyInfo> dependencyInfos, String suffix) {
    Preconditions.checkArgument(!dependencyInfos.isEmpty(),
        String.format("Empty dependencyInfo for key: %s", key));
    NewDependencyInfo dependencyInfo = Iterables.getFirst(dependencyInfos, null);
    TypeName returnType = key.getTypeName();
    ClassName injectorClassName = getPackagedInjectorForNewDependencyInfo(key, dependencyInfo);
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(injectorClassName);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix);
    methodSpecBuilder.addModifiers(Modifier.PUBLIC).returns(returnType);

    methodSpecBuilder.addStatement("$T result = new $T<>()", returnType, HashMap.class);
    TypeName mapKeyType = ((ParameterizedTypeName) returnType).typeArguments.get(0);
    TypeName mapValueType = ((ParameterizedTypeName) returnType).typeArguments.get(1);
    NewBindingKey mapValueKey = NewBindingKey.get(mapValueType);
    methodSpecBuilder.addStatement("$T mapKey", mapKeyType);
    methodSpecBuilder.addStatement("$T mapValue", mapValueType);
    for (NewDependencyInfo di : dependencyInfos) {
      AnnotationMirror mapKeyMirror =
          Utils.getAnnotationMirrorWithMetaAnnotation(di.getProvisionMethodElement(), MapKey.class);
      AnnotationValue unwrapValueAnnotationValue =
          Utils.getAnnotationValue(mapKeyMirror, "unwrapValue");
      if (unwrapValueAnnotationValue != null && !((boolean) unwrapValueAnnotationValue.getValue())) {
        errors.add(
            String.format("unwrapValue = false not supported yet. Consider using set binding."));
        return;
      }
      Object mapKey = Utils.getAnnotationValue(mapKeyMirror, "value").getValue();
      methodSpecBuilder.addStatement("mapKey = ($T) $S", mapKeyType, mapKey);
      if (Utils.isMapWithBuiltinValueType(key)) {
        TypeElement scope = scopeCalculator.calculate(key);
        methodSpecBuilder.addStatement("mapValue = $L",
            createAnonymousBuiltinTypeForMultiBinding(injectorClassName, mapValueKey, scope, di));
      } else {
        addNewStatementToMethodSpec(mapValueKey, di, injectorClassName, methodSpecBuilder,
            "mapValue");
      }
      methodSpecBuilder.addStatement("result.put(mapKey, mapValue)");
    }
    methodSpecBuilder.addStatement("return result");
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
    // messager.printMessage(Kind.NOTE, String.format(
    // "generateSetTypeProvisionMethodFromModule: \n key: %s, \n injector: %s, \n method: %s.",
    // key, injectorClassName, methodSpecBuilder.build()));
  }

  /**
   * Only applicable to bindings from modules. This is just a convenience method. Use the other
   * {@link #addNewStatementToMethodSpec} if scope can not be calculated from key, e.g., value key
   * for Map<String, Provider<Foo>>.
   */
  private void addNewStatementToMethodSpec(NewBindingKey key, NewDependencyInfo dependencyInfo,
      ClassName packagedInjectorClassName, MethodSpec.Builder methodSpecBuilder,
      String newVarName) {
    TypeElement scope = scopeCalculator.calculate(key);
    addNewStatementToMethodSpec(scope, dependencyInfo, packagedInjectorClassName, methodSpecBuilder,
        newVarName);
  }

  private void addNewStatementToMethodSpec(TypeElement scope, NewDependencyInfo dependencyInfo,
      ClassName packagedInjectorClassName, MethodSpec.Builder methodSpecBuilder,
      String newVarName) {
    Preconditions.checkNotNull(dependencyInfo.getProvisionMethodElement());

    StringBuilder builder = new StringBuilder("$L = $N().$N(");
    if (dependencyInfo.getDependencies().size() > 0) {
      for (NewBindingKey dependentKey : Utils
          .getDependenciesFromExecutableElement(dependencyInfo.getProvisionMethodElement())) {
        generateProvisionMethodAndAppendAsParameter(dependentKey,
            dependencyInfo.getSourceClassElement(),
            packagedInjectorClassName, builder);
      }
      builder.delete(builder.length() - 2, builder.length());
    }
    builder.append(")");
    methodSpecBuilder.addStatement(builder.toString(), newVarName,
        getGetModuleMethod(scope, dependencyInfo),
        dependencyInfo.getProvisionMethodElement().getSimpleName());
  }

  private MethodSpec getGetModuleMethod(NewBindingKey key, NewDependencyInfo dependencyInfo) {
    TypeElement scope = scopeCalculator.calculate(key);
    return getGetModuleMethod(scope, dependencyInfo);
  }
    
  private MethodSpec getGetModuleMethod(TypeElement scope, NewDependencyInfo dependencyInfo) {
    Preconditions.checkArgument(dependencyInfo.getProvisionMethodElement() != null,
        String.format("Expect one from module but get %s.", dependencyInfo));
    TypeElement module = dependencyInfo.getSourceClassElement();
    ClassName packagedInjectorClassName =
        getPackagedInjectorForNewDependencyInfo(scope, dependencyInfo);
    if (!modulesWithGetter.containsKey(packagedInjectorClassName)) {
      modulesWithGetter.put(packagedInjectorClassName, new HashMap<ClassName, MethodSpec>());
    }
    if (!modulesWithGetter.get(packagedInjectorClassName).containsKey(ClassName.get(module))) {
      generateGetModuleMethod(scope, dependencyInfo);
    }

    return modulesWithGetter.get(packagedInjectorClassName).get(ClassName.get(module));
  }

  /**
   * Creates get_Foo_Module() for FooModule.
   */
  private void generateGetModuleMethod(NewBindingKey key, NewDependencyInfo dependencyInfo) {
    TypeElement scope = scopeCalculator.calculate(key);
    generateGetModuleMethod(scope, dependencyInfo);
  }

  /**
   * Creates get_Foo_Module() for FooModule.
   */
  private void generateGetModuleMethod(TypeElement scope, NewDependencyInfo dependencyInfo) {
    Preconditions.checkArgument(dependencyInfo.getProvisionMethodElement() != null,
        String.format("Expect one from module but get %s.", dependencyInfo));
    TypeElement module = dependencyInfo.getSourceClassElement();
    TypeName moduleTypeName = TypeName.get(module.asType());
    ClassName packagedInjectorClassName =
        getPackagedInjectorForNewDependencyInfo(scope, dependencyInfo);
    TypeSpec.Builder componentSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    String moduleCanonicalNameConverted = Utils.getQualifiedName(module).replace(".", "_");

    componentSpecBuilder.addField(moduleTypeName, moduleCanonicalNameConverted, Modifier.PRIVATE);

    MethodSpec.Builder methodBuilder =
        MethodSpec.methodBuilder(Utils.getGetMethodName(module)).addModifiers(Modifier.PRIVATE)
            .addStatement("$T result = $N", module, moduleCanonicalNameConverted)
            .returns(moduleTypeName);
    methodBuilder.beginControlFlow("if (result == null)");
    if (!isPassedModule(module)) {
      methodBuilder.addStatement("result = $N = new $T()", moduleCanonicalNameConverted, module);
    } else {
      methodBuilder.addStatement("result = $N = $L.$N()", moduleCanonicalNameConverted,
          TOP_LEVEL_INJECTOR_FIELD, Utils.getGetMethodName(module));
    }
    methodBuilder.endControlFlow();
    methodBuilder.addStatement("return result");
    MethodSpec methodSpec = methodBuilder.build();
    componentSpecBuilder.addMethod(methodSpec);

    modulesWithGetter.get(packagedInjectorClassName).put(ClassName.get(module), methodSpec);
  }

  /**
   * Returns if the module is passed in to top level injector.
   */
  private boolean isPassedModule(TypeElement module) {
    return nonNullaryCtorModules.values().contains(module)
        || nonNullaryCtorUnscopedModules.contains(module);
  }

  // Returns provide_Blah_Qualifier__Foo_Type for @BlahQualifier FooType.
  private String getProvisionMethodName(NewBindingKey key) {
    return "provide_" + getSourceCodeName(key);
  }

  /**
   * Returns {@link #getSourceCodeName}(qualifier) + "__" + {@link #getSourceCodeName} (type), or
   * {@link #getSourceCodeName}(type) if no qualifier.
   */
  private String getSourceCodeName(NewBindingKey key) {
    // System.out.println("getMethodName for key: " + key);
    StringBuilder builder = new StringBuilder();
    AnnotationSpec qualifier = key.getQualifier();
    if (qualifier != null) {
      ClassName qualifierType = (ClassName) qualifier.type;
      builder.append(Utils.getSourceCodeName(qualifierType));
      /**
       * TODO(freeman): handle all illegal chars.
       */
      if (Utils.getCanonicalName(qualifierType).equals(Named.class.getCanonicalName())) {
        builder.append("_").append(qualifier.members.get("value").toString().replace("\"", "_")
            .replace("[", "_").replace("]", "_"));
      }
      builder.append("__");
    }
    builder.append(Utils.getSourceCodeName(key.getTypeName()));
    return builder.toString();
  }

  /**
   * For key like javax.inject.Provider<Foo> and dagger.Lazy<Foo>. Qualifier, if presented, will
   * also apply to element binding.
   */
  private void generateProvisionMethodForProviderOrLazy(NewBindingKey key,
      TypeElement referencingClass, String suffix) {
    // System.out.println(String.format(
    // "generateProvisionMethodForProviderOrLazy: key %s, referencingClass: %s, suffix : %s.", key,
    // referencingClass, suffix));

    ClassName injectorClassName = getInjectorFor(key, referencingClass);
    TypeSpec anonymousTypeSpec =
        createAnonymousBuiltinTypeForUniqueBinding(injectorClassName, key, referencingClass);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(key.getTypeName()).addStatement("return $L", anonymousTypeSpec);
    Builder componentSpecBuilder =
        getInjectorTypeSpecBuilder(getInjectorFor(key, referencingClass));
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private TypeSpec createAnonymousBuiltinTypeForUniqueBinding(ClassName leafInjectorClassName,
      NewBindingKey key, TypeElement referencingClass) {
    return createAnonymousBuiltinType(leafInjectorClassName, key, null /* scope */, 
        referencingClass, null /* dependencyInfo */);
  }

  private TypeSpec createAnonymousBuiltinTypeForMultiBinding(ClassName leafInjectorClassName,
      NewBindingKey key, TypeElement scope, NewDependencyInfo dependency) {
    return createAnonymousBuiltinType(leafInjectorClassName, key, scope,
        null /* referencingClass */, dependency);
  }

  /**
   * Generate for either unique a binding or a contributor to a multi-binding. NewDependencyInfo is
   * null for unique one and non-null or multi-binding one. ReferencingClass is the opposite, for
   * multi-binding, it is the module in dependency. Scope is null for unique binding.
   */
  private TypeSpec createAnonymousBuiltinType(ClassName leafInjectorClassName, NewBindingKey key,
      @Nullable TypeElement scope, @Nullable TypeElement referencingClass,
      @Nullable NewDependencyInfo newDependencyInfo) {
    Preconditions.checkArgument(key.getTypeName() instanceof ParameterizedTypeName);
    boolean isMultiBinding = newDependencyInfo != null;
    if (isMultiBinding) {
      Preconditions.checkArgument(referencingClass == null);
      Preconditions.checkNotNull(scope);
    } else {
      Preconditions.checkNotNull(referencingClass);
    }
    TypeName rawTypeName = ((ParameterizedTypeName) key.getTypeName()).rawType;
    Preconditions.checkArgument(Utils.hasBuiltinBinding(key),
        String.format("Built-in binding expected(Provider or Lazy), but get %s", key));
    boolean isLazy = rawTypeName.equals(ClassName.get(Lazy.class));
    NewBindingKey elementKey = Utils.getElementKeyForBuiltinBinding(key);
    Preconditions.checkNotNull(elementKey);
    if (!isMultiBinding) {
      generateProvisionMethodIfNeeded(elementKey, referencingClass);
    }
    MethodSpec.Builder builderForGet =
        MethodSpec.methodBuilder("get").returns(elementKey.getTypeName())
            .addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);

    builderForGet.addStatement("$T var = null", elementKey.getTypeName());
    if (isLazy) {
      builderForGet.beginControlFlow("if (var == null)");
    }
    if (!isMultiBinding) {
      builderForGet.addStatement("var = $N()", getProvisionMethodName(elementKey));
    } else {
      addNewStatementToMethodSpec(scope, newDependencyInfo, leafInjectorClassName, builderForGet, "var");
    }
    if (isLazy) {
      builderForGet.endControlFlow();
    }
    builderForGet.addStatement("return var");

    TypeSpec result = TypeSpec.anonymousClassBuilder("").addSuperinterface(key.getTypeName())
        .addMethod(builderForGet.build()).build();

    return result;
  }

  private void generateScopedProvisionMethod(
      Builder componentSpecBuilder, NewBindingKey key) {
    MethodSpec.Builder builder =
        MethodSpec.methodBuilder(getProvisionMethodName(key))
            .returns(key.getTypeName())
            .addModifiers(Modifier.PUBLIC);
    builder
        .addStatement("$T result = $N", key.getTypeName().box(), getFieldName(key))
        .beginControlFlow("if (result == null)")
        .beginControlFlow("synchronized ($L)", TOP_LEVEL_INJECTOR_FIELD)
        .addStatement("result = $N", getFieldName(key)).beginControlFlow("if (result == null)")
        .addStatement("result = $L = $L()", getFieldName(key),
            getProvisionMethodName(key) + UNSCOPED_SUFFIX)
        .endControlFlow() // if
        .endControlFlow() // synchronized
        .endControlFlow() // if
        .addStatement("return result");
    componentSpecBuilder.addMethod(builder.build());
  }

  private void generateField(Builder componentSpecBuilder, NewBindingKey key) {
    FieldSpec.Builder builder =
        FieldSpec.builder(key.getTypeName().box(), getFieldName(key), Modifier.PRIVATE);
    componentSpecBuilder.addField(builder.build());
  }

  private String getFieldName(NewBindingKey key) {
    return getSourceCodeName(key);
  }

  private void generateProvisionMethodForSet(NewBindingKey key, TypeElement referencingClass,
      String suffix) {
    TypeSpec.Builder componentSpecBuilder =
        getInjectorTypeSpecBuilder(getInjectorFor(key, referencingClass));

    // messager.printMessage(Kind.NOTE, "generateProvisionMethodForSet: " + key +
    // " PackagedInjector: "
    // + getInjectorFor(key, referencingClass) + " SpecBuilder: " + componentSpecBuilder);
    ParameterizedTypeName type = (ParameterizedTypeName) key.getTypeName();
    Preconditions.checkArgument(type.rawType.equals(ClassName.get(Set.class)));
    TypeName elementType = Iterables.getOnlyElement(type.typeArguments);

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(type);

    methodSpecBuilder.addStatement("$T result = new $T<>()", type, HashSet.class);
    methodSpecBuilder.addStatement("$T setVar", type);
    methodSpecBuilder.addStatement("$T elementVar", elementType);
    SetMultimap<PackageElement, NewDependencyInfo> packageToDependencyInfoMap =
        HashMultimap.create();
    for (NewDependencyInfo dependencyInfo : dependencies.get(key)) {
      packageToDependencyInfoMap.put(Utils.getPackage(dependencyInfo.getSourceClassElement()),
          dependencyInfo);
    }
    for (PackageElement pkg : packageToDependencyInfoMap.keySet()) {
      // messager.printMessage(Kind.NOTE, String.format("generateProvisionMethodForSet for %s from"
      // +
      // " %s", key, packageToDependencyInfoMap.get(pkg)));

      generateSetTypeProvisionMethodForPackage(key, packageToDependencyInfoMap.get(pkg), suffix);
      NewDependencyInfo dependencyInfo =
          Iterables.getFirst(packageToDependencyInfoMap.get(pkg), null);
      Preconditions.checkNotNull(dependencyInfo,
          String.format("no dependencyInfo for set key %s in module %s", key, pkg));
      ClassName packagedInjectorClassName =
          getPackagedInjectorForNewDependencyInfo(key, dependencyInfo);
      methodSpecBuilder.addStatement("setVar = $L.$N().$N()", TOP_LEVEL_INJECTOR_FIELD,
          Utils.getGetMethodName(packagedInjectorClassName), getProvisionMethodName(key));
      methodSpecBuilder.addStatement("result.addAll($L)", "setVar");
    }
    methodSpecBuilder.addStatement("return result");
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private void generateProvisionMethodForMap(final NewBindingKey key, TypeElement referencingClass,
      String suffix) {
    TypeSpec.Builder componentSpecBuilder =
        getInjectorTypeSpecBuilder(getInjectorFor(key, referencingClass));

    // messager.printMessage(Kind.NOTE, "generateProvisionMethodForSet: " + key +
    // " PackagedInjector: "
    // + getInjectorFor(key, referencingClass) + " SpecBuilder: " + componentSpecBuilder);
    ParameterizedTypeName type = (ParameterizedTypeName) key.getTypeName();
    Preconditions.checkArgument(type.rawType.equals(ClassName.get(Map.class)));

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(type);

    methodSpecBuilder.addStatement("$T result = new $T<>()", type, HashMap.class);
    methodSpecBuilder.addStatement("$T packagedMap", type);
    SetMultimap<PackageElement, NewDependencyInfo> packageToDependencyInfoMap =
        HashMultimap.create();
    Set<NewDependencyInfo> dependencyInfos = Utils.getDependencyInfo(dependencies, key);
    Preconditions
        .checkNotNull(dependencyInfos, String.format("dependencyInfo not found for key: %s", key));
    for (NewDependencyInfo dependencyInfo : Utils.getDependencyInfo(dependencies, key)) {
      packageToDependencyInfoMap.put(Utils.getPackage(dependencyInfo.getSourceClassElement()),
          dependencyInfo);
    }
    for (PackageElement pkg : packageToDependencyInfoMap.keySet()) {
      // messager.printMessage(Kind.NOTE, String.format("generateProvisionMethodForSet for %s from"
      // +
      // " %s", key, packageToDependencyInfoMap.get(pkg)));

      generateMapTypeProvisionMethodForPackage(key, packageToDependencyInfoMap.get(pkg), suffix);
      NewDependencyInfo dependencyInfo =
          Iterables.getFirst(packageToDependencyInfoMap.get(pkg), null);
      Preconditions.checkNotNull(dependencyInfo,
          String.format("no dependencyInfo for set key %s in module %s", key, pkg));
      ClassName packagedInjectorClassName =
          getPackagedInjectorForNewDependencyInfo(key, dependencyInfo);
      methodSpecBuilder.addStatement("packagedMap = $L.$N().$N()", TOP_LEVEL_INJECTOR_FIELD,
          Utils.getGetMethodName(packagedInjectorClassName), getProvisionMethodName(key));
      methodSpecBuilder.addStatement("result.putAll($L)", "packagedMap");
    }
    methodSpecBuilder.addStatement("return result");
    componentSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  private void generateInjectionMethod(NewBindingKey key, TypeElement referencingClass) {
    generateInjectionMethod(getClassFromKey(key), getInjectorFor(key, referencingClass), "inject",
        true);
  }

  private void generateInjectionMethod(TypeElement cls, TypeElement scope) {
    generateInjectionMethod(cls, getPackagedInjectorNameOfScope(Utils.getPackageString(cls), scope),
        "inject", true);
  }

  private void generateInjectionMethod(TypeElement cls, ClassName packagedInjectorClassName,
      String methodName, boolean isPublic) {
    if (!injectedClassNamesForPackagedInjector.put(packagedInjectorClassName, ClassName.get(cls))) {
      return;
    }

    // messager.printMessage(Kind.NOTE,
    // String.format("generateInjectionMethod. cls: %s, injector: %s, method: %s", cls,
    // packagedInjectorClassName, methodName));

    Builder componentSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);
    MethodSpec.Builder methodSpecBuilder = MethodSpec.methodBuilder(methodName)
        .addModifiers(isPublic ? Modifier.PUBLIC : Modifier.PRIVATE)
        .addParameter(ClassName.get(cls), "arg");

    // Inject closest ancestor first.
    TypeElement clsClosestInjectAncestor = getSuper(cls);
    while (clsClosestInjectAncestor != null
        && !Utils.hasInjectedFieldsOrMethods(clsClosestInjectAncestor, processingEnv)) {
      clsClosestInjectAncestor = getSuper(clsClosestInjectAncestor);
    }
    if (clsClosestInjectAncestor != null) {
      TypeElement scope =
          getComponentFromPackagedInjectorClassName(packagedInjectorClassName).getScope();
      generateInjectionMethod(clsClosestInjectAncestor, scope);
      ClassName ancestorPackagedInjector =
          getPackagedInjectorNameOfScope(Utils.getPackageString(clsClosestInjectAncestor), scope);
      StringBuilder stringBuilder = new StringBuilder();
      if (!ancestorPackagedInjector.equals(packagedInjectorClassName)) {
        stringBuilder.append(TOP_LEVEL_INJECTOR_FIELD).append(".$N().");
      }
      stringBuilder.append("inject(($T) arg)");
      if (!ancestorPackagedInjector.equals(packagedInjectorClassName)) {
        methodSpecBuilder.addStatement(stringBuilder.toString(),
            Utils.getGetMethodName(ancestorPackagedInjector),
            ClassName.get(clsClosestInjectAncestor));
      } else {
        // TODO(freeman): ClassName.get() removed the type parameters for now. Support it.
        methodSpecBuilder.addStatement(stringBuilder.toString(),
            ClassName.get(clsClosestInjectAncestor));
      }
    }

    for (VariableElement field : Utils.getInjectedFields(cls, processingEnv)) {
      // System.out.println("generateInjectionMethod. field: " + field);
      TypeMirror fieldType = field.asType();
      AnnotationMirror fieldQualifier = Utils.getQualifier(field);
      NewBindingKey fieldKey = NewBindingKey.get(fieldType, fieldQualifier);
      StringBuilder stringBuilder =
          new StringBuilder("arg.").append(field.getSimpleName()).append(" = ");
      addCallingProvisionMethod(stringBuilder, fieldKey, cls, packagedInjectorClassName);
      methodSpecBuilder.addStatement(stringBuilder.toString());
    }

    for (ExecutableElement method : Utils.getInjectedMethods(cls, processingEnv)) {
      // System.out.println("generateInjectionMethod. method: " + method);
      StringBuilder builder = new StringBuilder("arg.").append(method.getSimpleName()).append("(");
      List<NewBindingKey> methodArgs = Utils.getDependenciesFromExecutableElement(method);
      if (methodArgs.size() > 0) {
        for (NewBindingKey dependentKey : methodArgs) {
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

  /**
   * Returns super element, null if no super.
   */
  private TypeElement getSuper(TypeElement typeElement) {
    TypeMirror typeMirror = typeElement.getSuperclass();
    if (typeMirror.getKind().equals(TypeKind.NONE)) {
      return null;
    }
    return (TypeElement) ((DeclaredType) typeMirror).asElement();
  }

  /**
   * Adds "[topLevelInjector.getxxxPackagedInjector.]getxxx()" to the builder.
   */
  private void addCallingProvisionMethod(StringBuilder stringBuilder, NewBindingKey key,
      TypeElement referencingClass, ClassName packagedInjectorClassName) {
    generateProvisionMethodIfNeeded(key, referencingClass);
    ClassName originalPackagedInjector = getInjectorFor(key, referencingClass);
    ComponentInfo givenComponent =
        getComponentFromPackagedInjectorClassName(packagedInjectorClassName);
    ClassName targetPackagedInjectorClassName =
        getInjectorNameOfScope(originalPackagedInjector, givenComponent.getScope());
    if (!targetPackagedInjectorClassName.equals(packagedInjectorClassName)) {
      // System.out.println("addCallingProvisionMethod. packageInjector: " +
      // packagedInjectorClassName
      // + " field key: " + key);
      stringBuilder.append(TOP_LEVEL_INJECTOR_FIELD).append(".")
          .append(Utils.getGetMethodName(targetPackagedInjectorClassName)).append("().");
    }
    stringBuilder.append(getProvisionMethodName(key)).append("()");
  }

  /**
   * Handles generic.
   */
  private void generateProvisionMethodFromClass(NewBindingKey key, TypeElement referencingClass,
      String suffix) {
    // System.out.println(
    // "generateProvisionMethodFromClass. key: " + key + " referencingClass: " +
    // referencingClass);
    ClassName packagedInjectorClassName = getInjectorFor(key, referencingClass);
    TypeSpec.Builder injectorSpecBuilder = getInjectorTypeSpecBuilder(packagedInjectorClassName);

    TypeElement cls = getClassFromKey(key);

    ExecutableElement ctor = Utils.findInjectedCtor(cls, processingEnv);
    Preconditions.checkNotNull(ctor, String.format("Did not find ctor for %s", cls));
    List<NewBindingKey> dependencyKeys = Utils.getDependenciesFromExecutableElement(ctor);
    if (key.getTypeName() instanceof ParameterizedTypeName) {
      List<NewBindingKey> specializedKeys = new ArrayList<>();
      Map<TypeVariableName, TypeName> map =
          Utils.getMapFromTypeVariableToSpecialized((ParameterizedTypeName) key.getTypeName(),
              (ParameterizedTypeName) TypeName.get(cls.asType()));
      for (NewBindingKey k : dependencyKeys) {
        specializedKeys.add(Utils.specializeIfNeeded(k, map));
      }
      dependencyKeys = specializedKeys;
    }
    // System.out.println("generateProvisionMethodFromClass. dependencyKeys: " +
    // dependencyKeys);

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix);
    methodSpecBuilder.addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
        .returns(key.getTypeName());

    StringBuilder builder = new StringBuilder("$T result = new $T(");
    if (dependencyKeys.size() > 0) {
      for (NewBindingKey dependencyKey : dependencyKeys) {
        // System.out.println("generateProvisionMethodFromClass. dependencyKey: "
        // + dependencyKey);
        generateProvisionMethodAndAppendAsParameter(dependencyKey, cls,
            packagedInjectorClassName, builder);
      }
      builder.delete(builder.length() - 2, builder.length());
    }
    builder.append(")");
    methodSpecBuilder.addStatement(builder.toString(), key.getTypeName(), key.getTypeName());

    if (Utils.hasInjectedFieldsOrMethods(cls, processingEnv)) {
      // System.out.println("generateProvisionMethodFromClass. hasInjected");
      generateInjectionMethod(key, referencingClass);
      methodSpecBuilder.addStatement("inject(result)");
    }

    methodSpecBuilder.addStatement("return result");
    injectorSpecBuilder.addMethod(methodSpecBuilder.build());
  }

  // Handles generic case.
  private TypeElement getClassFromKey(NewBindingKey key) {
    Preconditions.checkArgument(Iterables.getOnlyElement(Utils.getDependencyInfo(dependencies, key))
        .getProvisionMethodElement() == null, String.format("Key: %s", key));
    ClassName className;
    TypeName typeName = key.getTypeName();
    if (typeName instanceof ClassName) {
      className = (ClassName) typeName;
    } else {
      Preconditions.checkState(typeName instanceof ParameterizedTypeName,
          String.format("typeName: %s", typeName));
      className = ((ParameterizedTypeName) typeName).rawType;
    }
    String classNameString = Utils.getClassCanonicalName(className);
    TypeElement result = processingEnv.getElementUtils().getTypeElement(classNameString);
    Preconditions.checkNotNull(result,
        String.format("Did not find TypeElement for %s", classNameString));
    return result;
  }

  private void generateProvisionMethodAndAppendAsParameter(NewBindingKey key,
      TypeElement referencingClass, ClassName packagedInjectorClassName,
      StringBuilder builder) {
    TypeElement scope =
        getComponentFromPackagedInjectorClassName(packagedInjectorClassName).getScope();
    generateProvisionMethodIfNeeded(key, referencingClass);
    ClassName dependencyPackagedInjectorClassName =
        getInjectorOfScope(key, referencingClass, scope);
    if (!dependencyPackagedInjectorClassName.equals(packagedInjectorClassName)) {
      builder.append(TOP_LEVEL_INJECTOR_FIELD).append(".")
          .append(Utils.getGetMethodName(dependencyPackagedInjectorClassName)).append("().");
    }
    builder.append(getProvisionMethodName(key)).append("(), ");
  }

  /**
   * Returns {@link ClassName} of the injector in the same package as the give injectorClassName but
   * for the given scope.
   */
  private static ClassName getInjectorNameOfScope(ClassName injectorClassName, TypeElement scope) {
    String packageString = getPackageFromInjectorClassName(injectorClassName);
    boolean isMultiBindingInjector = Utils.isMultiBindingInjector(injectorClassName);
    String simpleName = isMultiBindingInjector ? Utils.getMultiBindingInjectorSimpleName(scope)
        : Utils.getPackagedInjectorSimpleName(scope);
    return ClassName.get(packageString, simpleName);
  }

  private void generateTopLevelInjectors() {
    SetMultimap<NewBindingKey, ClassName> keyToPackagedInjectorMap =
        Utils.reverseSetMultimap(generatedBindingsForPackagedInjector);

    // messager.printMessage(Kind.NOTE,
    // "generatedBindingsForPackagedInjector: " + generatedBindingsForPackagedInjector);
    // messager.printMessage(Kind.NOTE, "keyToPackagedInjectorMap: " + keyToPackagedInjectorMap);

    Set<ClassName> topLevelInjectedClassNames = new HashSet<>();
    for (ComponentInfo component : orderedComponents) {
      TypeSpec.Builder injectorBuilder = TypeSpec
          .classBuilder(getTopLevelInjectorName(component)).addAnnotation(AnnotationSpec
              .builder(Generated.class).addMember("value", "$S", GENERATOR_NAME).build())
          .addModifiers(Modifier.PUBLIC);

      // Member injector interfaces.
      for (TypeElement injector : memberInjectors.get(component)) {
        injectorBuilder.addSuperinterface(TypeName.get(injector.asType()));
      }

      MethodSpec.Builder ctorBuilder =
          MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

      // Containing top level injector.
      String containingInjectorName = "containingInjector";
      if (componentTree.get(component) != null) {
        ClassName containingInjectorClassName = ClassName.get(topLevelPackageString,
            getTopLevelInjectorName(componentTree.get(component)));
        injectorBuilder.addField(containingInjectorClassName, containingInjectorName,
            Modifier.PRIVATE);
        ctorBuilder.addParameter(containingInjectorClassName, containingInjectorName)
            .addStatement("this.$L = $L", containingInjectorName, containingInjectorName);
      }

      // Passed modules.
      Set<TypeElement> allPassedModules = new HashSet<>();
      allPassedModules.addAll(nonNullaryCtorModules.get(component));
      allPassedModules.addAll(nonNullaryCtorUnscopedModules);
      for (TypeElement passedModule : Utils.sortByFullName(allPassedModules)) {
        String moduleName = Utils.getSourceCodeName(passedModule);
        ClassName moduleTypeName = (ClassName) TypeName.get(passedModule.asType());
        ctorBuilder.addParameter(moduleTypeName, moduleName).addStatement("this.$N = $N",
            moduleName, moduleName);
        injectorBuilder.addField(moduleTypeName, moduleName, Modifier.PRIVATE)
            .addMethod(MethodSpec.methodBuilder(Utils.getGetMethodName(moduleTypeName))
                .addModifiers(Modifier.PUBLIC).returns(moduleTypeName)
                .addStatement("return $N", moduleName).build());
      }

      injectorBuilder.addMethod(ctorBuilder.build());

      // Injection methods and non-injection methods.
      Set<String> miscMethodNames = new HashSet<>();
      Set<TypeElement> allMembersInjectors = Sets.newHashSet(memberInjectors.get(component));
      while (!allMembersInjectors.isEmpty()) {
        TypeElement injector = Iterables.getFirst(allMembersInjectors, null);
        Preconditions.checkNotNull(injector, String.format("Empty allMembersInjector."));
        allMembersInjectors.remove(injector);
        for (TypeMirror parentInterface : injector.getInterfaces()) {
          allMembersInjectors.add((TypeElement) ((DeclaredType) parentInterface).asElement());
        }
        for (Element element : injector.getEnclosedElements()) {
          if (!element.getKind().equals(ElementKind.METHOD)) {
            continue;
          }

          // Injection methods.
          if (Utils.isInjectionMethod(element)) {
            ExecutableElement method = (ExecutableElement) element;
            TypeMirror typeMirror = Iterables.getOnlyElement(method.getParameters()).asType();
            if (typeMirror.getKind().equals(TypeKind.TYPEVAR)) {
              // TODO(freeman): support generic injection method.
              continue;
            }
            TypeElement cls = (TypeElement) ((DeclaredType) typeMirror).asElement();
            if (!topLevelInjectedClassNames.add(ClassName.get(cls))) {
              continue;
            }

            ClassName packagedInjectorClassName =
                getPackagedInjectorNameOfScope(Utils.getPackageString(cls), component.getScope());
            injectorBuilder.addMethod(MethodSpec.methodBuilder(method.getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC).addParameter(ClassName.get(cls), "arg")
                .addStatement("$L().inject(arg)",
                    Utils.getGetMethodName(packagedInjectorClassName))
                .build());
          } else if (Utils.isProvisionMethodInInjector(element)) {
            // non-injection methods, provision methods or getSubComponent method in
            // editors. NOTE(freeman): subcomponent should be converted to component.
            ExecutableElement method = (ExecutableElement) element;

            if (!miscMethodNames.add(method.getSimpleName().toString())) {
              continue;
            }
            MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder(method.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC).returns(TypeName.get(method.getReturnType()));
            NewBindingKey providedKey = Utils.getKeyProvidedByMethod(method);
            ClassName packagedInjectorClassName = null;
            for (ClassName className : keyToPackagedInjectorMap.get(providedKey)) {
              if (isInjectorOfScope(className, component.getScope())) {
                packagedInjectorClassName = className;
                break;
              }
            }
            if (packagedInjectorClassName == null) {
              messager.printMessage(Kind.WARNING,
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
              methodBuilder.addStatement(statement,
                  Utils.getGetMethodName(packagedInjectorClassName),
                  getProvisionMethodName(providedKey));
            }
            // System.out.println("provision method added: " + methodBuilder.build());
            injectorBuilder.addMethod(methodBuilder.build());
          } else {
            messager.printMessage(Kind.WARNING,
                String.format("Element %s ignored from injector %s.", element, injector));
          }
        }
      }

      // Methods to get injectors.
      for (Map.Entry<ClassName, TypeSpec.Builder> entry : packagedInjectorBuilders.entrySet()) {
        ClassName injectorClassName = entry.getKey();
        if (!component.equals(getComponentFromPackagedInjectorClassName(injectorClassName))) {
          continue;
        }
        String packagedInjectorSourceCodeName = Utils.getSourceCodeName(injectorClassName);
        injectorBuilder.addField(injectorClassName, packagedInjectorSourceCodeName,
            Modifier.PRIVATE);
        MethodSpec.Builder methodSpecBuilder =
            MethodSpec.methodBuilder(Utils.getGetMethodName(injectorClassName))
                .addModifiers(Modifier.PUBLIC).returns(injectorClassName)
                .addStatement("$T result = $N", injectorClassName, packagedInjectorSourceCodeName)
                .beginControlFlow("if (result == null)");

        StringBuilder stringBuilder = new StringBuilder("result = $N = new $T(this");
        if (componentTree.get(component) != null) {
          ClassName containingPackageInjectorClassName =
              getInjectorNameOfScope(injectorClassName, componentTree.get(component).getScope());
          stringBuilder.append(", ").append(containingInjectorName).append(".")
              .append(Utils.getGetMethodName(containingPackageInjectorClassName)).append("()");
        }
        stringBuilder.append(")");
        methodSpecBuilder.addStatement(stringBuilder.toString(), packagedInjectorSourceCodeName,
            injectorClassName);

        methodSpecBuilder.endControlFlow().addStatement("return result");
        injectorBuilder.addMethod(methodSpecBuilder.build());
      }

      generateInjectorBuilder(component, injectorBuilder);

      JavaFile javaFile = JavaFile.builder(topLevelPackageString, injectorBuilder.build()).build();
      try {
        javaFile.writeTo(processingEnv.getFiler());
      } catch (IOException e) {
        Throwables.propagate(e);
      }
    }
  }

  private boolean isInjectorOfScope(ClassName injectorClassName, TypeElement scope) {
    return injectorClassName.simpleName()
        .contains(scope.getQualifiedName().toString().replace(".", "_"));
  }

  private void generateInjectorBuilder(ComponentInfo componentInfo,
      TypeSpec.Builder injectorBuilder) {
    TypeSpec.Builder builderBuilder =
        TypeSpec.classBuilder("Builder").addModifiers(Modifier.PUBLIC, Modifier.STATIC);

    ClassName injectorClassName =
        ClassName.get(topLevelPackageString, getTopLevelInjectorName(componentInfo));
    boolean hasParent = componentTree.get(componentInfo) != null;
    ClassName parentClassName = null;

    // set parent inject methods.
    if (hasParent) {
      parentClassName = ClassName.get(topLevelPackageString,
          getTopLevelInjectorName(componentTree.get(componentInfo)));
      Utils.addSetMethod(injectorClassName, builderBuilder, parentClassName);
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
    allNonNullaryCtorModules.addAll(nonNullaryCtorModules.get(componentInfo));
    allNonNullaryCtorModules.addAll(nonNullaryCtorUnscopedModules);
    for (TypeElement m : allNonNullaryCtorModules) {
      Utils.addSetMethod(injectorClassName, builderBuilder, (ClassName) ClassName.get(m.asType()));
    }

    // build() method.
    MethodSpec.Builder buildMethodBuilder =
        MethodSpec.methodBuilder("build").addModifiers(Modifier.PUBLIC).returns(injectorClassName);
    StringBuilder returnCodeBuilder = new StringBuilder("return new $T(");
    if (hasParent) {
      String parentInjectorFieldName = Utils.getSourceCodeName(parentClassName);
      returnCodeBuilder.append(parentInjectorFieldName);
      if (!allNonNullaryCtorModules.isEmpty()) {
        returnCodeBuilder.append(", ");
      }
    }
    if (!allNonNullaryCtorModules.isEmpty()) {
      for (TypeElement module : Utils.sortByFullName(allNonNullaryCtorModules)) {
        String moduleFiledName = Utils.getSourceCodeName(TypeName.get(module.asType()));
        returnCodeBuilder.append(moduleFiledName).append(", ");
      }
      int size = returnCodeBuilder.length();
      returnCodeBuilder.delete(size - 2, size);
    }
    returnCodeBuilder.append(");");
    buildMethodBuilder.addCode(returnCodeBuilder.toString(), injectorClassName);
    builderBuilder.addMethod(buildMethodBuilder.build());

    injectorBuilder.addType(builderBuilder.build());
  }

  public String getTopLevelInjectorName(ComponentInfo component) {
    return topLevelInjectorPrefix + component.getName() + topLevelInjectorSuffix;
  }
}
