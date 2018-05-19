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

import com.google.common.base.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Generated;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;

class HubInjectorGenerator4 extends GeneralInjectorGenerator4 {
  private static String TAG = "HubInjectorGenerator4";
  private static final String GENERATOR_NAME = "dagger." + TAG;

  static Set<String> generatedGenericInjectors = new HashSet();

  private final Set<BindingKey> bindsInstances;
  private final TypeElement parentEitherComponent;
  private SetMultimap<TypeElement, TypeElement> eitherComponentToChildrenMap =
      HashMultimap.create();

  private final TypeElement eitherComponent;
  private final Set<String> packagesWithInjectorGenerated = new HashSet<>();
  private final Map<TypeElement, TypeElement> componentToParentMap;
  private final ExtraDependenciesOnParentCalculator extraDependenciesOnparentCalculator;
  private SetMultimap<TypeElement, BindingKey> componentToKeyMap;

  public HubInjectorGenerator4(
      TypeElement eitherComponent,
      SetMultimap<BindingKey, DependencyInfo> dependencies,
      Set<TypeElement> modules,
      Map<TypeElement, TypeElement> componentToParentMap,
      SetMultimap<TypeElement, BindingKey> componentToKeyMap,
      Set<TypeElement> componentDependencies,
      Set<BindingKey> bindsInstances,
      ProcessingEnvironment env, Utils utils) {
    super(
        // completeDependencies(
        //     eitherComponent,
        //     componentToParentMap.get(eitherComponent),
        //     dependencies,
        //     componentToParentMap,
        //     env,
        //     utils),
        dependencies,
        modules,
        componentDependencies,
        env,
        utils);
    this.bindsInstances = bindsInstances;
    this.eitherComponent = eitherComponent;
    this.componentToParentMap = componentToParentMap;
    for (Map.Entry<TypeElement, TypeElement> i : componentToParentMap.entrySet()) {
      eitherComponentToChildrenMap.put(i.getValue(), i.getKey());
    }
    this.parentEitherComponent = componentToParentMap.get(eitherComponent);
    this.componentToKeyMap = componentToKeyMap;
    this.extraDependenciesOnparentCalculator =
        ExtraDependenciesOnParentCalculator.getInstance(componentToParentMap, env, utils);
    logger.w("(sub)component: %s", eitherComponent);
    if (eitherComponent.getSimpleName().contentEquals("ApplicationComponent")) {
      // generatedBindings.setDebugEnabled(true);
    }
  }

  // private static SetMultimap<BindingKey, DependencyInfo> completeDependencies(
  //     TypeElement eitherComponent,
  //     TypeElement parentEitherComponent,
  //     SetMultimap<BindingKey, DependencyInfo> dependencies,
  //     Map<TypeElement, TypeElement> componentToParentMap,
  //     ProcessingEnvironment env,
  //     Utils utils) {
  //   SetMultimap<BindingKey, DependencyInfo> result = HashMultimap.create(dependencies);
  //   for (BindingKey key : ExtraDependenciesOnParentCalculator.getInstance(componentToParentMap, env, utils)
  //       .calculate(eitherComponent, parentEitherComponent)) {
  //     result.put(key, new DependencyInfo())
  //   }
  //   return result;
  // }


  @Override
  protected String getPackageString() {
    return utils.getPackage(eitherComponent).getQualifiedName().toString();
  }

  @Override
  protected String getInjectorSimpleName() {
    return utils.getComponentImplementationSimpleNameFromInterface(eitherComponent);
  }

  @Override
  protected Set<TypeName> getSuperInterfaces() {
    Set<TypeName> result = new HashSet<>();
    result.add(TypeName.get(eitherComponent.asType()));
    // result.addAll(utils.collectPackagedHubInterfaces(eitherComponent, dependencies));
    result.addAll(getChildSubcomponentParentInterfaces(eitherComponent));
    // result.add(collectSubcomponentHubInterfaces();
    return result;
  }

  private Set<TypeName> getChildSubcomponentParentInterfaces(
      TypeElement eitherComponent) {
    Set<TypeName> result = new HashSet<>();
    for (TypeElement i : eitherComponentToChildrenMap.get(eitherComponent)) {
      if (!utils.isSubcomponent(i)) {
        continue;
      }
      result.add(
          getSubcomponentParentInterfaceClassName(i));
    }
    return result;
  }

  private ClassName getSubcomponentParentInterfaceClassName(TypeElement subcomponent) {
    return ClassName.get(
        utils.getPackageString(subcomponent),
        SubcomponentParentInterfaceGenerator.getInterfaceName(subcomponent, utils));
  }

  /** Returns provided and injected. */
  @Override
  protected Pair<Set<BindingKey>, Set<BindingKey>> getProduced() {
    return utils.getProduced(eitherComponent);
  }

  @Override
  protected void preGenerateProduced() {
    utils.generateDebugInfoMethod(
        injectorBuilder,
        "debugInfo",
        String.format(
            "dependencies: %s, extra: %s",
            dependencies,
            extraDependenciesOnparentCalculator.calculate(
                eitherComponent, componentToParentMap.get(eitherComponent))));
    generateInherited();
  }

  private void generateInherited() {
    if (!utils.isSubcomponent(eitherComponent)) {
      return;
    }
    utils.generateDebugInfoMethod(injectorBuilder, "generateInherited");

    for (BindingKey key :
        extraDependenciesOnparentCalculator.calculate(eitherComponent, null)) {
      String provisionMethodName = getProvisionMethodName(key);
      if (!generatedBindings.add(provisionMethodName)) {
        continue;
      }
      injectorBuilder.addMethod(
          MethodSpec.methodBuilder(provisionMethodName)
              .addModifiers(Modifier.PUBLIC)
              .returns(key.getTypeName())
              .addStatement(
                  "return $L.$L()",
                  utils.getSourceCodeName(getSubcomponentParentInterfaceClassName(eitherComponent)),
                  provisionMethodName)
              .build());
    }
  }

  @Override
  protected void postGenerateProduced() {
    logger.n("started");
    // TODO: remove this, just and the contracts to trigger generating things when needed.
    // generateImplicitMethods();

    // generateInheritedProvisionMethods();
    generateProvisionAndInjectionMethods();
    // TODO: ctor injected classes with scope different from the (sub)component should be
    // inherited below.s
    // generateImplicitProvisionMethods();
    // generateForPackagedHubInterfaces();
    generateForChildren();

    // Builder.
    TypeElement explicitBuilder = utils.findBuilder(elements, eitherComponent);
    generateInjectorBuilder(explicitBuilder);

    // builder().

    ClassName builderClassName =
        ClassName.get(
            utils.getPackageString(eitherComponent),
            utils.getComponentImplementationSimpleNameFromInterface(eitherComponent),
            explicitBuilder != null ? explicitBuilder.getSimpleName().toString() : "Builder");
    MethodSpec.Builder builderMethodSpecBuilder =
        MethodSpec.methodBuilder("builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(builderClassName);
    boolean isSubcomponent = utils.isSubcomponent(eitherComponent);
    if (isSubcomponent) {
      builderMethodSpecBuilder.addParameter(
          getSubcomponentParentInterfaceClassName(eitherComponent), "v");
    }
    builderMethodSpecBuilder.addCode(
        "return new $T($L);", builderClassName, isSubcomponent ? "v" : "");
    injectorBuilder.addMethod(builderMethodSpecBuilder.build());

    // provision methods for (sub)component builders that can be provided by this core injector.
    // TODO: handle implicit ones here, explicit ones have been handled in {@link
    // #generateProvisionMethodForThoseFromTopLevel}.
    // generateImplicitProvisionMethodForEitherComponentBuilder(injectorBuilder, builder);
  }

  private void generateForChildren() {
    for (TypeElement child : eitherComponentToChildrenMap.get(eitherComponent)) {
      utils.generateDebugInfoMethod(injectorBuilder,
          "generateForChildren_" + child.getQualifiedName());
      for (BindingKey key: extraDependenciesOnparentCalculator
          .calculate(child, eitherComponent)) {
        /**
         * Handling box. {@link #getProvisionMethodName(BindingKey)} will always return the binding
         * (un)boxed. But {@link SubcomponentParentInterfaceGenerator} cannot do that. So here we
         * generated the one that could have been missed otherwise.
         */
        Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, key);
        if (dependencyInfos != null) {
          generateProvisionMethodIfNeeded(key);
          if (key.getTypeName().isPrimitive() || key.getTypeName().isBoxedPrimitive()) {
            DependencyInfo dependencyInfo = Iterables.getOnlyElement(dependencyInfos);
            if (!key.equals(dependencyInfo.getDependant())) {
              String provisionMethodName = Utils.getProvisionMethodName(key);
              if (!generatedBindings.add(provisionMethodName)) {
                continue;
              }
              injectorBuilder.addMethod(
                  MethodSpec.methodBuilder(provisionMethodName)
                      .addModifiers(Modifier.PUBLIC)
                      .returns(key.getTypeName())
                      .addStatement(
                          "return $L()",
                          getProvisionMethodName(key))
                      .build());
            }
          }
        } else {
          // throw new RuntimeException("What key cannot be bound? " + key);
          /** TODO: fix this by fxing {@link ExtraDependenciesOnParentCalculator}
           *
           */
          logger.w("What key cannot be bound? ", key);
          generateEmptyProvisionMethodIfNeeded(key);
        }
      }
    }
  }

  private void generateImplicitProvisionMethods() {
    logger.w("count: %d", componentToKeyMap.get(eitherComponent).size());
    for (BindingKey key : componentToKeyMap.get(eitherComponent)) {
      if (utils.isPublicRecurively(utils.getTypeFromKey(key))) {
        generateProvisionMethodIfNeeded(key);
      }
    }
  }

  private void generateInheritedProvisionMethods() {
    TypeElement directionParent = componentToParentMap.get(eitherComponent);
    TypeElement parent = directionParent;
    int count = 0;
    boolean isServiceComponent = eitherComponent.getQualifiedName().toString().contains("ServiceComponent");
    if (isServiceComponent) {
      // logger.e("eitherComponent: %s", eitherComponent);
    }
    while (parent != null) {
      count += componentToKeyMap.get(parent).size();
      if (isServiceComponent) {
        // logger.e("parent %s\nkeys:%s", parent, componentToKeyMap.get(parent));
      }
      for (BindingKey key : componentToKeyMap.get(parent)) {
        if ((key.getTypeName() instanceof ClassName
            && utils.isSubcomponent(utils.getTypeElement(key)))) {
          continue;
        }
        String provisionMethodName = getProvisionMethodName(key);
        if (!generatedBindings.add(provisionMethodName)) {
          continue;
        }
        injectorBuilder.addMethod(
            MethodSpec.methodBuilder(provisionMethodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(key.getTypeName())
                .addStatement(
                    "return (($T) $L).$L()",
                    ClassName.get(
                        utils.getPackageString(directionParent),
                        utils.getComponentImplementationSimpleNameFromInterface(directionParent)),
                    utils.getSourceCodeName(directionParent),
                    provisionMethodName)
                .build());
      }
      parent = componentToParentMap.get(parent);
    }
    logger.w("count: %d", count);
  }

  private void generateForPackagedHubInterfaces() {
    int count = 0;
    for(TypeName i : utils.collectPackagedHubInterfaces(eitherComponent, dependencies)) {
      TypeElement typeElement = utils.getTypeElementForClassName((ClassName) i);
      if (typeElement == null) {
        logger.e("package not found: %s", i);
        return;
      }
      utils.generateDebugInfoMethod(injectorBuilder, "debugInfo",
          "hubInterface: " + i);
      for (Element e : typeElement.getEnclosedElements()) {
        ExecutableElement method = (ExecutableElement) e;
        // logger.n("component: %s, method: %s", eitherComponent, method);
        if (utils.isProvisionMethodInInjector(method)) {
          count ++;
          BindingKey key = utils.getKeyProvidedByMethod(method);
          // should be changed to generate all method instead of required so that we can avoid
          // complex logic to detect required binding from decendents.
          if (utils.getDependencyInfo(dependencies, key) != null) {
            utils.generateDebugInfoMethod(
                injectorBuilder,
                "generateForPackagedHubInterface" + typeElement.getQualifiedName(),
                String.format(
                    "key:%s, di: %s", key, utils.getDependencyInfo(dependencies, key).toString()));
            generateProvisionMethodForPackagedHubInterfaces(key);
          } else {
            // in lower scope, just generate dummy which will never be called.
            generateEmptyProvisionMethodIfNeeded(key);
          }
        } else {
          Preconditions.checkState(utils.isInjectionMethod(method));
          count ++;
          BindingKey key =
              utils.getKeyForOnlyParameterOfMethod(
                  types, (DeclaredType) utils.getTypeFromTypeName(i), method);

          if (allDependenciesFulfilledForInjectionMethod(key)) {
            // logger.n("fulfilled");
            generateInjectionMethod(key);
          } else {
            // in lower scope, just generate dummy which will never be called.
            // logger.n("empty");
            generateEmptyInjectionMethodFor(key);
          }
        }
      }
    }
    logger.w("count: %d", count);
  }

  /**
   * Handles primitive or boxed ones.
   */
  private void generateProvisionMethodForPackagedHubInterfaces(BindingKey key) {
    generateProvisionMethodIfNeeded(key);

    DependencyInfo dependencyInfo =
        Iterables.getFirst(utils.getDependencyInfo(dependencies, key), null);
    if (dependencyInfo == null) {
      // throw new RuntimeException(
      //     String.format("dependency not found for key: %s, dI: %s", key, dependencyInfo));
      logger.w("(sub)component: %s, dependency not found for key: %s, dI: %s", eitherComponent, key, dependencyInfo);
      return;
    }
    if ((key.getTypeName().isBoxedPrimitive() || key.getTypeName().isPrimitive())
        && !key.equals(dependencyInfo.getDependant())) {
      logger.n("handling box for key: %s", key);
      Preconditions.checkState(key.boxOrUnbox().equals(dependencyInfo.getDependant()));
      String methodName =
          PackagedHubInterfaceGenerator.getProvisionMethodNameForPackagedHubInterface(key);
      if (!generatedBindings.add(methodName)) {
        return;
      }
      TypeMirror returnType = dependencyInfo.getProvisionMethodElement().getReturnType();
      BindingKey returnKey = BindingKey.get(returnType, key.getQualifier());
      MethodSpec.Builder methodSpecBuilder =
          MethodSpec.methodBuilder(methodName);
      methodSpecBuilder
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .returns(key.getTypeName());
      methodSpecBuilder.addStatement("return $L()", getProvisionMethodName(key));
      injectorBuilder.addMethod(methodSpecBuilder.build());
    }
  }

  private boolean allDependenciesFulfilledForInjectionMethod(BindingKey key) {
    Set<BindingKey> requiredKeys = new HashSet<>();
    utils.collectRequiredKeysFromClass(requiredKeys, utils.getTypeElement(key));
    for (BindingKey k : requiredKeys) {
      if (utils.getDependencyInfo(dependencies, k) == null) {
        logger.n("%s cannot be provided for %s.", k, key);
        return false;
      }
    }
    return true;
  }

  // @Override
  // protected void generateSetContributors(BindingKey key, MethodSpec.Builder methodSpecBuilder) {
  //   Set<String> packages = getPackagesFromKeyProvidedByModules(key);
  //   for (String p : packages) {
  //     methodSpecBuilder.beginControlFlow("");
  //     methodSpecBuilder.addStatement("$T contributor", key.getTypeName());
  //     generateGetPackagedInjectorMethodIfNeeded(p);
  //     methodSpecBuilder.addStatement("$L = $L().$L()", "contributor",
  //         utils.getGetMethodName(getPackagedInjectorClassName(p)),
  //         Utils.getProvisionMethodName(dependencies, key));
  //       methodSpecBuilder.addStatement("result.addAll(contributor)");
  //     methodSpecBuilder.endControlFlow();
  //   }
  // }

  private Set<String> getPackagesFromKeyProvidedByModules(BindingKey key) {
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, key);
    Set<TypeElement> modules = new HashSet<>();
    for (DependencyInfo dependencyInfo : dependencyInfos) {
      Preconditions.checkState(
          dependencyInfo.getDependencySourceType().equals(DependencySourceType.MODULE));
      modules.add(dependencyInfo.getSourceClassElement());
    }
    return utils.getPackages(modules, new HashSet<>());
  }

  // protected boolean shouldInjectAfterCreation() {
  //   return true;
  // }

  // @Override
  // protected void generateMapContributors(BindingKey key, ParameterizedTypeName returnType,
  //     MethodSpec.Builder methodSpecBuilder) {
  //   Set<String> packages = getPackagesFromKeyProvidedByModules(key);
  //   for (String p : packages) {
  //     methodSpecBuilder.beginControlFlow("");
  //     methodSpecBuilder.addStatement("$T contributor", key.getTypeName());
  //     generateGetPackagedInjectorMethodIfNeeded(p);
  //     methodSpecBuilder.addStatement("$L = $L().$L()", "contributor",
  //         utils.getGetMethodName(getPackagedInjectorClassName(p)),
  //         Utils.getProvisionMethodName(dependencies, key));
  //     methodSpecBuilder.addStatement("result.putAll(contributor)");
  //     methodSpecBuilder.endControlFlow();
  //   }
  // }

  private void generateEmptyProvisionMethodIfNeeded(BindingKey key) {

    String provisionMethodName =
        PackagedHubInterfaceGenerator.getProvisionMethodNameForPackagedHubInterface(key);
    if (!generatedBindings.add(provisionMethodName)) {
      return;
    }
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(provisionMethodName)
        .addModifiers(Modifier.PUBLIC)
        .returns(key.getTypeName());

    methodSpecBuilder.addStatement(
        "throw new $T($S)",
        RuntimeException.class,
        "This is a place holder and should not have been called.");
    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  private void generateEmptyInjectionMethodFor(BindingKey key) {
    if (!injectionMethodsDone.add(Pair.of("inject", key.getTypeName()))) {
      // logger.w("inject key: %s", key);
      return;
    }
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder("inject")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(key.getTypeName(), "v");

    methodSpecBuilder.addStatement(
        "throw new $T($S)",
        RuntimeException.class,
        "This is a place holder and should not have been called.");
    injectorBuilder.addMethod(methodSpecBuilder.build());
  }
  private void generateProvisionAndInjectionMethods() {
    // Injection methods and non-injection methods.
    Set<String> miscMethodNames = new HashSet<>();
    DeclaredType eitherComponentType = (DeclaredType) eitherComponent.asType();
    final int[] count = {0};
    utils.generateDebugInfoMethod(injectorBuilder, "generateProvisionAndInjectionMethods");
    utils.traverseAndDo(
        types,
        eitherComponentType,
        eitherComponent,
        p -> {
          Element element = p.second;
          // logger.n("" + element);
          if (!element.getKind().equals(ElementKind.METHOD)) {
            return null;
          }
          ExecutableElement method = (ExecutableElement) element;
          ExecutableType methodType =
              (ExecutableType) processingEnv.getTypeUtils().asMemberOf(eitherComponentType, method);
          // Injection methods.
          if (utils.isInjectionMethod(element)) {

            // TODO: add duplicate check for provision method also.
            if (injectionMethodsDone.add(
                    Pair.of(
                        method.getSimpleName().toString(),
                        TypeName.get(Iterables.getOnlyElement(methodType.getParameterTypes()))))
                == false) {
              // logger.w("injection method: " + method);
              return null;
            }

            TypeMirror typeMirror = Iterables.getOnlyElement(methodType.getParameterTypes());
            TypeElement cls = (TypeElement) ((DeclaredType) typeMirror).asElement();
            // logger.n(methodType.toString());

            injectorBuilder.addMethod(
                MethodSpec.methodBuilder(method.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassName.get(cls), "arg")
                    .addStatement("inject(arg)")
                    .build());
            count[0]++;
          } else if (utils.isComponentProvisionMethod(element)) {
            logger.l(Kind.ERROR, "Injecting components is not supported: " + element);
          } else if (utils.isSubcomponentProvisionMethod(element)) {
            /** TODO: handle this in {@link #generateProvisionMethodIfNeeded(BindingKey)} */
            generateGetSubcomponentMethod((ExecutableElement) element, injectorBuilder);
            count[0]++;

          } else if (utils.isProvisionMethodInInjector(element)) {
            /**
             * TODO: handle it in the way consistent with other {@link DependencySourceType} in
             * {@link #generateProvisionMethodIfNeeded(BindingKey, TypeElement)}.
             */
            // non-injection methods, provision methods or getSubComponent method in
            // editors. NOTE(freeman): subcomponent should be converted to component.

            if (!miscMethodNames.add(method.getSimpleName().toString())) {
              return null;
            }
            MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder(method.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.get(method.getReturnType()));
            BindingKey providedKey = utils.getKeyProvidedByMethod(method);
            // ClassName packagedInjectorClassName = null;
            // for (ClassName className : keyToPackagedInjectorMap.get(providedKey)) {
            //   if (isInjectorOfScope(className, coreInjectorInfo.getScope())) {
            //     packagedInjectorClassName = className;
            //     break;
            //   }
            // }
            // if (packagedInjectorClassName == null) {
            //   logger.l(Kind.WARNING,
            //       String.format(
            //           "PackagedInjector or multiBindingInjector not found for key: %s "
            //               + "from provisionMethod: %s. Probably it is not used.",
            //           providedKey, method));
            //   // Create a dumb method
            //   String statement = "return ";
            //   TypeKind typeKind = method.getReturnType().getKind();
            //   if (typeKind.equals(TypeKind.BOOLEAN)) {
            //     statement += "false";
            //   } else if (typeKind.equals(TypeKind.CHAR)) {
            //     statement += "\'0\'";
            //   } else if (typeKind.isPrimitive()) {
            //     statement += "0";
            //   } else {
            //     statement += "null";
            //   }
            //   methodBuilder.addStatement(statement);
            // } else {
            String statement = "return $L()";
            methodBuilder.addStatement(
                statement, Utils.getProvisionMethodName(dependencies, providedKey));
            // }
            // logger.n("method added: " + methodBuilder.build());
            injectorBuilder.addMethod(methodBuilder.build());
            count[0]++;
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
          } else if (utils.isIrrelevantMethodInInjector(element)) {
            // do nothing
          } else {
            logger.l(
                Kind.WARNING, "Element %s ignored from injector %s.", element, eitherComponentType);
          }
          return null;
        });
    logger.w("count: %d", count[0]);
  }

  @Override
  protected String getProvisionMethodName(BindingKey key) {
    return Utils.getProvisionMethodName(dependencies, key);
  }

  /**
   * Subcomponents has 1 ctor parameter, which is the parent (sub)component. Components has 0 ctor
   * parameter and 0 or more dependecies.
   */
  protected void generateInjectorBuilder(@Nullable TypeElement expliciteBuilder) {
    logger.n("component: " + eitherComponent + " explicit: " + (expliciteBuilder != null));
    Preconditions.checkArgument(
        utils.isEitherComponent(eitherComponent),
        "Expect (sub)component, but found: " + eitherComponent);
    boolean explicit = expliciteBuilder != null;
    boolean isSubcomponent = utils.isSubcomponent(eitherComponent);

    // logger.n("pos: 1");

    String packageString = utils.getPackageString(eitherComponent);
    String generatedComponentSimpleName =
        utils.getComponentImplementationSimpleNameFromInterface(eitherComponent);
    ClassName componentClassName = ClassName.get(packageString, generatedComponentSimpleName);

    // logger.n("pos: 2");
    // Generate class header.
    String builderName = explicit ? expliciteBuilder.getSimpleName().toString() : "Builder";
    Builder builderBuilder =
        TypeSpec.classBuilder(builderName).addModifiers(Modifier.PUBLIC, Modifier.STATIC);
    // logger.n("pos: 3");
    if (explicit) {
      ElementKind kind = expliciteBuilder.getKind();
      ClassName superName = ClassName.get(eitherComponent).nestedClass(builderName);
      if (kind.equals(ElementKind.INTERFACE)) {
        builderBuilder.addSuperinterface(superName);
      } else {
        Preconditions.checkState(
            kind.equals(ElementKind.CLASS),
            TAG + " unexpected kind for builder: " + expliciteBuilder);
        builderBuilder.superclass(superName);
      }
    }

    // logger.n("pos: 4");
    // ctor for subcomponent.
    if (isSubcomponent) {
      TypeName parentComponentTypeName = getSubcomponentParentInterfaceClassName(eitherComponent);
      String parentComponentSourceCodeName = Utils.getSourceCodeName(parentComponentTypeName);
      builderBuilder.addField(
          FieldSpec.builder(
              parentComponentTypeName, parentComponentSourceCodeName, Modifier.PRIVATE)
              .build());
      MethodSpec.Builder ctorBuilder =
          MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
      ctorBuilder
          .addParameter(parentComponentTypeName, parentComponentSourceCodeName)
          .addCode("this.$N = $N;", parentComponentSourceCodeName, parentComponentSourceCodeName);
      builderBuilder.addMethod(ctorBuilder.build());
    }

    // logger.n("pos: 5");
    /** Set deps methods. */
    for (TypeElement m : componentDependencies) {
      String methodName =
          explicit
              ? Preconditions.checkNotNull(
              utils.getBuilderSetterName(types, elements, expliciteBuilder, m))
              : null;

      logger.l(Kind.NOTE, "componentDependency: %s", m);
      utils.addSetMethod(
          types, elements, componentClassName,
          builderBuilder,
          ClassName.get(m.asType()),
          methodName,
          builderName);
    }

    // logger.n("pos: 6");
    /** Set @BindsInstance methods. */
    /** TODO: refactor this. see {@link #collectComponentToBindsInstanceMap()} */
    // if (isSubcomponent) {
    //   instanceDeps.remove(BindingKey.get(componentToParentMap.get(component)));
    // } else {
    //   if (dependencyComponent != null) {
    //     instanceDeps.remove(BindingKey.get(dependencyComponent));
    //   }
    // }
    // for (TypeElement typeElement :componentToComponentDependencyMap.get(component)) {
    //   instanceDeps.remove(BindingKey.get(typeElement));
    // }
    // for (TypeElement typeElement :sortedPassedModules) {
    //   instanceDeps.remove(BindingKey.get(typeElement));
    // }

    logger.l(        Kind.NOTE, "instanceDeps" + bindsInstances);
    for (BindingKey key : bindsInstances) {
      String methodName =
          explicit
              ? Preconditions.checkNotNull(
              utils.getBuilderSetterName(types, elements, expliciteBuilder, key))
              : null;
      ClassName builderParentClassName = componentClassName;
      if (explicit) {
        ExecutableElement setter = utils.getBuilderSetter(types, elements, expliciteBuilder, key);
        if (setter.getReturnType().getKind() == TypeKind.VOID) {
          builderParentClassName = null;
        }
      }

      logger.l(Kind.NOTE, "@BindsInstance " + key);
      utils.addSetMethod(
          types,
          elements,
          builderParentClassName,
          builderBuilder,
          key,
          methodName,
          builderName);
    }
    // logger.n("pos: 7");

    /** Set module methods. */
    for (TypeElement m : nonNullaryCtorModules) {
      String methodName =
          explicit
              ? Preconditions.checkNotNull(
              utils.getBuilderSetterName(types, elements, expliciteBuilder, m))
              : null;

      logger.l(Kind.NOTE, "nonNullaryCtorModules: %s", m);
      utils.addSetMethod(
          types, elements, componentClassName,
          builderBuilder,
          ClassName.get(m.asType()),
          methodName,
          builderName);
    }

    // build() method.
    String methodName =
        explicit
            ? utils.getBuildMethod(processingEnv.getTypeUtils(), elements, expliciteBuilder)
            .getSimpleName()
            .toString()
            : "build";
    MethodSpec.Builder buildMethodBuilder =
        MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC)
            .returns(componentClassName);
    StringBuilder returnCodeBuilder = new StringBuilder("return new $T(");
    for (BindingKey key : utils.sortBindingKeys(getAllCtorParameters())) {
      returnCodeBuilder.append(Utils.getSourceCodeNameHandlingBox(key, dependencies)).append(", ");
    }

    if (returnCodeBuilder.charAt(returnCodeBuilder.length() - 1) != '(') {
      returnCodeBuilder.delete(returnCodeBuilder.length() - 2, returnCodeBuilder.length());
    }

    returnCodeBuilder.append(");");
    logger.l(Kind.NOTE, "generateInjectorBuilder, return string: %s", returnCodeBuilder.toString());
    buildMethodBuilder.addCode(returnCodeBuilder.toString(), componentClassName);
    builderBuilder.addMethod(buildMethodBuilder.build());

    injectorBuilder.addType(builderBuilder.build());
  }

  @Override
  protected Set<BindingKey> getAllCtorParameters() {
    Set<BindingKey> allParameters = new HashSet<>();
    for (TypeElement dep : componentDependencies) {
      // Preconditions.checkState(
      //     !utils.isEitherComponent(dep),
      //     "(sub)component " + dep + " found in component dependencies. ");
      allParameters.add(BindingKey.get(dep));
    }
    allParameters.addAll(bindsInstances);
    for (TypeElement typeElement : nonNullaryCtorModules) {
      allParameters.add(BindingKey.get(typeElement));
    }
    if (utils.isSubcomponent(eitherComponent)) {
      allParameters.add(
          BindingKey.get(
              getSubcomponentParentInterfaceClassName(eitherComponent)));
    }
    return allParameters;
  }

  /** Generates those methods that are not declared in the (sub)component interface. */
  protected void generateImplicitMethods() {
    utils.traverseAndDo(
        types,
        (DeclaredType) eitherComponent.asType(),
        eitherComponent,
        p -> {
          Element e = p.second;
          // logger.n("element: " + e);
          if (!utils.isMethod(e)) {
            return null;
          }
          ExecutableElement method = (ExecutableElement) e;
          ExecutableType methodType = (ExecutableType) p.first;
          if (utils.isInjectionMethod(method)) {

            TypeElement injectedTypeElement =
                (TypeElement)
                    ((DeclaredType) Iterables.getOnlyElement(methodType.getParameterTypes()))
                        .asElement();
            logger.l(                Kind.NOTE,
                "injection method for: " + injectedTypeElement);

            generateInjectionMethod(injectedTypeElement, "inject");

          } else if (utils.isProvisionMethodInInjector(method)) {
            generateProvisionMethodIfNeeded(
                utils.getKeyProvidedByMethod(method));
          } else {
            // TODO: ignore known elements like builders.
            logger.w(   "Unknown element %s from injector %s.", method, eitherComponent);
          }
          return null;
        });

    // logger.n("packagedInjectorBuilders: " + packagedInjectorBuilders);

    // Inherited provision methods.
    // for (CoreInjectorInfo component : orderedCoreinjectors) {
    //   if (componentTree.get(component) == null) {
    //     continue;
    //   }
    //   for (Map.Entry<ClassName, Builder> entry : packagedInjectorBuilders.entrySet()) {
    //     ClassName packagedInjectorClassName = entry.getKey();
    //     if (!component
    //         .equals(getComponentFromPackagedInjectorClassName(packagedInjectorClassName))) {
    //       continue;
    //     }
    //     generateInheritedProvisionMethods(packagedInjectorClassName);
    //   }
    // }

    // Inherited injection methods.
    // for (CoreInjectorInfo component : orderedCoreinjectors) {
    //   if (componentTree.get(component) == null) {
    //     continue;
    //   }
    //   for (Map.Entry<ClassName, Builder> entry : packagedInjectorBuilders.entrySet()) {
    //     ClassName packagedInjectorClassName = entry.getKey();
    //     if (!component
    //         .equals(getComponentFromPackagedInjectorClassName(packagedInjectorClassName))) {
    //       continue;
    //     }
    //     generateInheritedInjectionMethods(packagedInjectorClassName);
    //   }
    // }

    // JavaFile javaFile =
    // JavaFile.builder(utils.getPackage(eitherComponent).getQualifiedName().toString(),
    // builder.build()).build();

    // logger.l(    //     Kind.NOTE, "javaFile for: " + builder.build() + "\n" + javaFile.toString());

    //       logger.n("file: %s", javaFile));
    //       try {
    //         // javaFile.writeTo(processingEnv.getFiler());
    //       } catch (IOException e) {
    //         Throwables.propagate(e);
    //       }
  }

  // @Override
  // protected void generateInjectionMethodBody(
  //     TypeElement cls, MethodSpec.Builder methodSpecBuilder) {
  //   String packageString = utils.getPackageString(cls);
  //   generateGetPackagedInjectorMethodIfNeeded(packageString);
  //   methodSpecBuilder.addStatement("$L().inject(arg)",
  //       utils.getGetMethodName(getPackagedInjectorClassName(packageString)));
  // }

  @Override
  protected void addNewStatementToMethodSpec(
      MethodSpec.Builder methodSpecBuilder, DependencyInfo dependencyInfo, String newVarName) {
    // Pair<DependencyInfo, BindingKey> pair = handleBinds(dependencyInfo);
    // if (pair.first == null) { // from parent
    //   methodSpecBuilder.addStatement(
    //       "$L = $L",
    //       newVarName,
    //       generateProvisionMethodAndReturnCallingString(pair.second)
    //   );
    //   return;
    // }
    // dependencyInfo = pair.first;
    if (dependencyInfo.getDependencySourceType().equals(DependencySourceType.CTOR_INJECTED_CLASS) &&
        utils.isKeyByGenericClass(dependencyInfo.getDependant())) {
      addNewStatementToMethodSpecForGenericClass(methodSpecBuilder, dependencyInfo, newVarName);
      return;
    }
    ExecutableElement provisionMethodElement = dependencyInfo.getProvisionMethodElement();
    String packageString = utils.getPackageString(dependencyInfo.getSourceClassElement());
    switch (dependencyInfo.getDependencySourceType()) {
      // TODO: handle generic
      case CTOR_INJECTED_CLASS:
      case MODULE:
        // logger.n("%s", dependencyInfo);
        if (dependencyInfo.getDependencySourceType().equals(DependencySourceType.MODULE) &&
            utils.isBindsMethod(dependencyInfo.getProvisionMethodElement())) {
          String provisionString;
          BindingKey key = utils.getKeyForOnlyParameterOfMethod(
              types,
              (DeclaredType) dependencyInfo.getSourceClassElement().asType(),
              dependencyInfo.getProvisionMethodElement());
          boolean needsCast = !utils.isPublicallyAccessible(key.getTypeName());
          provisionString = generateProvisionMethodAndReturnCallingString(key);

          if (needsCast) {
            methodSpecBuilder.addStatement(
                "$L = ($T) $L",
                newVarName,
                getAccessibleTypeName(
                    BindingKey.get(
                        Utils.getReturnTypeElement(dependencyInfo.getProvisionMethodElement()))),
                provisionString);

          } else {
            methodSpecBuilder.addStatement("$L = $L", newVarName, provisionString);
          }
        } else {
          methodSpecBuilder.addStatement("$L = $L", newVarName,
              generateStringCallingProxyProvisionMethod(dependencyInfo));
        }
        return;
      case DAGGER_MEMBERS_INJECTOR:
      case COMPONENT_DEPENDENCIES_METHOD:
      case COMPONENT_DEPENDENCIES_ITSELF:
      case EITHER_COMPONENT:
      case EITHER_COMPONENT_BUILDER:
      case BINDS_INTANCE:
        methodSpecBuilder.addStatement(
            "$L = $L",
            newVarName,
            generateProvisionMethodAndReturnCallingString(dependencyInfo.getDependant())
        );
        return;
      case NONE:
        logger.e(
            "unexpected dependency source type for %s, stack: %s",
            dependencyInfo, Lists.newArrayList(new RuntimeException().getStackTrace()));
    }
  }


  private void addNewStatementToMethodSpecForGenericClass(MethodSpec.Builder methodSpecBuilder,
      DependencyInfo dependencyInfo, String newVarName) {
    StringBuilder stringBuilder = new StringBuilder("$L = $L");
    methodSpecBuilder.addStatement(
        stringBuilder.toString(),
        newVarName,
        createStringProvidingKey(dependencyInfo.getDependant()));
  }

  private String createStringProvidingKey(BindingKey key) {
    // logger.n("key: %s", key);
    generateProvisionMethodIfNeeded(key);
    StringBuilder builder = new StringBuilder();
    TypeName typeName = key.getTypeName();
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, key);
    DependencyInfo dependencyInfo =
        dependencyInfos == null
            ? null
            : Preconditions.checkNotNull(
                Iterables.getFirst(dependencyInfos, null), "DI not found for key: " + key);
    if (dependencyInfo != null && dependencyInfo
            .getDependencySourceType()
            .equals(DependencySourceType.CTOR_INJECTED_CLASS)
        && typeName instanceof ParameterizedTypeName
        && !utils.isProviderOrLazy(key)
        && !utils.isOptional(key)) {
      generateGenericInjectorIfNeeded(dependencyInfo);
      // generic ctor injected class
      builder = new StringBuilder("new ")
      .append(utils.getGenericInjectorName(key)).append("().generate(");
      for (BindingKey k : utils.sortBindingKeys(dependencyInfo.getDependencies())) {
        builder.append(createStringProvidingKey(k)).append(", ");
      }
      utils.trimTrailing(builder, ", ");
      builder.append(")");
    } else {
      // types from hub or this
      builder.append(getProvisionMethodName(key)).append("()");
    }
    // logger.n("result: %s", builder.toString());
    return builder.toString();
  }

  private void generateGenericInjectorIfNeeded(DependencyInfo dependencyInfo) {
    BindingKey dependant = dependencyInfo.getDependant();
    if (!generatedGenericInjectors.add(utils.getGenericInjectorName(dependant))) {
      return;
    }
    Builder genericInjectorBuilder =
        TypeSpec.classBuilder(utils.getGenericInjectorSimpleName(dependant))
            .addAnnotation(
                AnnotationSpec.builder(Generated.class).addMember("value", "$S", GENERATOR_NAME).build())
            .addModifiers(Modifier.PUBLIC);
    TypeName returnType = dependant.getTypeName();
    MethodSpec.Builder generateBuilder = MethodSpec.methodBuilder("generate")
        .addModifiers(Modifier.PUBLIC)
        .returns(returnType);
    for (BindingKey key : utils.sortBindingKeys(dependencyInfo.getDependencies())) {
      generateBuilder.addParameter(key.getTypeName(), utils.getSourceCodeName(key));
    }

    DeclaredType type = (DeclaredType) utils.getTypeFromTypeName(
        returnType);
    TypeElement classElement = (TypeElement) type.asElement();
    ExecutableElement ctor = Preconditions.checkNotNull(utils.findInjectedCtor(classElement),
        "injector ctor not found for dI: " + dependencyInfo);

    TypeMirror ctorTypeMirror = types.asMemberOf(type, ctor);
    List<BindingKey> ctorDependencies =
        utils.getCtorDependencies(dependencies, dependant);

    StringBuilder statementBuilder = new StringBuilder("$T result = new $T(");
    for (BindingKey key: ctorDependencies) {
      statementBuilder.append(utils.getSourceCodeName(key)).append(", ");
    }
    utils.trimTrailing(statementBuilder, ", ");
    statementBuilder.append(")");
    generateBuilder.addStatement(statementBuilder.toString(), returnType, returnType);
    for (Element element : classElement.getEnclosedElements()) {
      if (element.getKind().equals(ElementKind.FIELD) && utils.isInjected(element)) {
        generateBuilder.addStatement(
            "result.$N = $L",
            element.getSimpleName(),
            utils.getSourceCodeName(utils.getKeyForField(type, element)));
      }
      if (element.getKind().equals(ElementKind.METHOD) && utils.isInjected(element)) {
        statementBuilder = new StringBuilder("result.$L(");
        for (BindingKey key: utils.getDependenciesFromMethod(
            (ExecutableType) types.asMemberOf(type, element), (ExecutableElement) element)) {
          statementBuilder.append(utils.getSourceCodeName(key)).append(", ");
        }
        utils.trimTrailing(statementBuilder, ", ");
        statementBuilder.append(")");
        generateBuilder.addStatement(statementBuilder.toString(), element.getSimpleName());
      }
    }

    generateBuilder.addStatement("return result");
    genericInjectorBuilder.addMethod(generateBuilder.build());
    JavaFile javaFile =
        JavaFile.builder(
            utils.getPackageString(utils.getClassFromKey(dependant)),
            genericInjectorBuilder.build()).build();
    try {
      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void generateGetPackagedInjectorMethodIfNeeded(String packageString) {
    if (!packagesWithInjectorGenerated.add(packageString)) {
      return;
    }
    generateGetPackagedInjectorMethod(packageString);
  }

  /** generate a getXXX() that returns xxx field, which is a {@link DoubleCheckLazyProvider}. */
  protected void generateGetPackagedInjectorMethod(String packageString) {
    generateGetPackagedInjectorMethodInternal(packageString);
    ClassName packagedInjectorClassName = getPackagedInjectorClassName(packageString);
    String packagedInjectorSourceName = Utils.getSourceCodeName(packagedInjectorClassName);
    TypeName fieldTypeName =
        ParameterizedTypeName.get(
            ClassName.get(DoubleCheckLazyProvider.class), packagedInjectorClassName);
    generateFieldIfNeeded(
        fieldTypeName,
        packagedInjectorSourceName,
        "$T.create(()->$L())",
        ClassName.get(DoubleCheckLazyProvider.class),
        getInternalPackagedInjectorGetterName(packagedInjectorClassName));
    injectorBuilder.addMethod(
        MethodSpec.methodBuilder(utils.getGetMethodName(packagedInjectorClassName))
            .addModifiers(Modifier.PRIVATE)
            .returns(packagedInjectorClassName)
            .addStatement("return $L.get()", packagedInjectorSourceName)
            .build());
  }

  /** generate a getXXX_internal() that xxx packaged injector. */
  protected void generateGetPackagedInjectorMethodInternal(String packageString) {
    ClassName packagedInjectorClassName = getPackagedInjectorClassName(packageString);

    StringBuilder statementBuilder = new StringBuilder("return new $T(");

    TypeElement packagedInjectorTypeElement = utils
        .getTypeElementForClassName(packagedInjectorClassName);
    if (packagedInjectorTypeElement == null) {
      logger.e("packaged injector not found: %s", packagedInjectorClassName);
      return;
    }
    ExecutableElement ctor = utils.findCtor(packagedInjectorTypeElement);
    for (BindingKey key : utils.getDependenciesFromExecutableElement(ctor)) {
      TypeName typeName = key.getTypeName();
      if ( true) {
        // if (typeName.equals(
        //     ClassName.get(packageString, PackagedHubInterfaceGenerator.HUB_INTERFACE))) {
        statementBuilder.append("this, ");
      } else { // nonNullaryCtor ones
        TypeElement module = utils.getTypeElement(key);
        Preconditions.checkState(utils.isModule(module));
        if (modules.contains(module)) {
          statementBuilder.append(utils.getSourceCodeName(typeName)).append(", ");
        } else {
          // modules belongs to lower scopes, whill never be used at this scope.
          statementBuilder.append("null, ");
        }
      }
    }
    if (statementBuilder.substring(statementBuilder.length() - 2).equals(", ")) {
      statementBuilder.delete(statementBuilder.length() - 2, statementBuilder.length());
    }
    statementBuilder.append(")");
    injectorBuilder.addMethod(
        MethodSpec.methodBuilder(
            getInternalPackagedInjectorGetterName(packagedInjectorClassName))
            .addModifiers(Modifier.PRIVATE)
            .returns(packagedInjectorClassName)
            .addStatement(
                statementBuilder.toString(), packagedInjectorClassName)
            .build());
  }

  private String getInternalPackagedInjectorGetterName(ClassName packagedInjectorClassName) {
    return utils.getGetMethodName(packagedInjectorClassName) + "_internal";
  }

  private ClassName getPackagedInjectorClassName(String packageString) {
    throw new RuntimeException("no packaged injector, proxy!");
    // return ClassName.get(packageString, PackagedInjectorGenerator.TIGER_PAKCAGED_INJECTOR);
  }


}
