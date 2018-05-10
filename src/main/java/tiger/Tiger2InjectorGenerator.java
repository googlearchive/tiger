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
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
class Tiger2InjectorGenerator extends GeneralInjectorGenerator {
  private static String TAG = "Tiger2InjectorGenerator";

  private final Set<BindingKey> bindsInstances;
  private final TypeElement parentEitherComponent;
  private final TypeElement eitherComponent;


  public Tiger2InjectorGenerator(
      TypeElement eitherComponent,
      SetMultimap<BindingKey, DependencyInfo> dependencies,
      Set<TypeElement> modules,
      @Nullable TypeElement parentEitherComponent,
      Set<TypeElement> componentDependencies,
      Set<BindingKey> bindsInstances,
      ProcessingEnvironment env, Utils utils) {
    super(dependencies, modules, componentDependencies, env, utils);
    this.bindsInstances = bindsInstances;
    this.eitherComponent = eitherComponent;
    this.parentEitherComponent = parentEitherComponent;
  }


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
    return Sets.newHashSet(TypeName.get(eitherComponent.asType()));
  }

  @Override
  protected Pair<Set<BindingKey>, Set<BindingKey>> getProduced() {
    Set<BindingKey> provided = new HashSet<>();
    Set<BindingKey> injected = new HashSet<>();

    DeclaredType eitherComponentType = (DeclaredType) eitherComponent.asType();
    utils.traverseAndDo(
        types,
        eitherComponentType,
        eitherComponent,
        p -> {
          Element element = p.second;
          messager.printMessage(Kind.NOTE, "method: " + element);
          if (!element.getKind().equals(ElementKind.METHOD)) {
            return null;
          }
          ExecutableElement method = (ExecutableElement) element;
          ExecutableType methodType =
              (ExecutableType) processingEnv.getTypeUtils().asMemberOf(eitherComponentType, method);
          // Injection methods.
          if (utils.isInjectionMethod(element)) {
            TypeMirror typeMirror = Iterables.getOnlyElement(methodType.getParameterTypes());
            injected.add(BindingKey.get(typeMirror));
          } else if (utils.isProvisionMethodInInjector(element)) {
            provided.add(utils.getKeyProvidedByMethod(method));
          } else if (isIrrelevantMethodInInjector(element)) {
            // do nothing
          } else {
            messager.printMessage(
                Kind.WARNING,
                String.format(
                    "Element %s ignored from injector %s.", element, eitherComponentType));
          }
          return null;
        });
    return Pair.of(provided, injected);
  }
  @Override
  protected void doSpecific() {
    // TODO: remove this, just and the contracts to trigger generating things when needed.
    // generateImplicitMethods();

    // Injection methods and non-injection methods.
    Set<String> miscMethodNames = new HashSet<>();
    DeclaredType eitherComponentType = (DeclaredType) eitherComponent.asType();
    utils.traverseAndDo(
        types,
        eitherComponentType,
        eitherComponent,
        p -> {
          Element element = p.second;
          messager.printMessage(Kind.NOTE, "method: " + element);
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
              messager.printMessage(Kind.WARNING, "duplicate injection method: " + method);
              return null;
            }

            TypeMirror typeMirror = Iterables.getOnlyElement(methodType.getParameterTypes());
            TypeElement cls = (TypeElement) ((DeclaredType) typeMirror).asElement();
            messager.printMessage(
                Kind.NOTE, TAG + ".generateTopLevelInjector-injection method : " + methodType);

            injectorBuilder.addMethod(
                MethodSpec.methodBuilder(method.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassName.get(cls), "arg")
                    .addStatement("inject(arg)")
                    .build());
          } else if (utils.isComponentProvisionMethod(element)) {
            messager.printMessage(Kind.ERROR, "Injecting components is not supported: " + element);
          } else if (utils.isSubcomponentProvisionMethod(element)) {
            /** TODO: handle this in {@link #generateProvisionMethodIfNeeded(BindingKey)} */
            generateGetSubcomponentMethod((ExecutableElement) element, injectorBuilder);
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
            //   messager.printMessage(Kind.WARNING,
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
                String.format(
                    "Element %s ignored from injector %s.", element, eitherComponentType));
          }
          return null;
        });

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
      builderMethodSpecBuilder.addParameter(ClassName.get(parentEitherComponent.asType()), "v");
    }
    builderMethodSpecBuilder.addCode(
        "return new $T($L);", builderClassName, isSubcomponent ? "v" : "");
    injectorBuilder.addMethod(builderMethodSpecBuilder.build());

    // provision methods for (sub)component builders that can be provided by this core injector.
    // TODO: handle implicit ones here, explicit ones have been handled in {@link
    // #generateProvisionMethodForThoseFromTopLevel}.
    // generateImplicitProvisionMethodForEitherComponentBuilder(injectorBuilder, builder);
  }

  @Override
  protected String getProvisionMethodName(BindingKey key) {
    return Utils.getProvisionMethodName(key);
  }

  /**
   * Subcomponents has 1 ctor parameter, which is the parent (sub)component. Components has 0 ctor
   * parameter and 0 or more dependecies.
   */
  protected void generateInjectorBuilder(@Nullable TypeElement expliciteBuilder) {
    messager.printMessage(
        Kind.NOTE,
        "generateInjectorBuilder, component: "
            + eitherComponent
            + " explicit: "
            + (expliciteBuilder != null));
    Preconditions.checkArgument(
        utils.isEitherComponent(eitherComponent),
        "Expect (sub)component, but found: " + eitherComponent);
    boolean explicit = expliciteBuilder != null;
    boolean isSubcomponent = utils.isSubcomponent(eitherComponent);

    messager.printMessage(Kind.NOTE, "generateInjectorBuilder, pos: 1");

    String packageString = utils.getPackageString(eitherComponent);
    String generatedComponentSimpleName =
        utils.getComponentImplementationSimpleNameFromInterface(eitherComponent);
    ClassName componentClassName = ClassName.get(packageString, generatedComponentSimpleName);

    messager.printMessage(Kind.NOTE, "generateInjectorBuilder, pos: 2");
    // Generate class header.
    String builderName = explicit ? expliciteBuilder.getSimpleName().toString() : "Builder";
    Builder builderBuilder =
        TypeSpec.classBuilder(builderName).addModifiers(Modifier.PUBLIC, Modifier.STATIC);
    messager.printMessage(Kind.NOTE, "generateInjectorBuilder, pos: 3");
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

    messager.printMessage(Kind.NOTE, "generateInjectorBuilder, pos: 4");
    // ctor for subcomponent.
    if (isSubcomponent) {
      TypeName parentComponentTypeName = TypeName.get(parentEitherComponent.asType());
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
    } else {
      // setter for parent component.
      ClassName dependencyClassName = null;
      if (explicit && parentEitherComponent != null) {
        dependencyClassName = (ClassName) ClassName.get(parentEitherComponent.asType());
        String methodName =
            explicit
                ? utils.getBuilderSetterName(
                types, elements, expliciteBuilder, parentEitherComponent)
                : null;
        if (methodName != null || !explicit) {
          utils.addSetMethod(
              types, elements, componentClassName, builderBuilder, dependencyClassName, methodName, builderName);
        }
      }
    }

    messager.printMessage(Kind.NOTE, "generateInjectorBuilder, pos: 5");
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

    messager.printMessage(Kind.NOTE, "generateInjectorBuilder, pos: 6");
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

    messager.printMessage(
        Kind.NOTE, TAG + ".generateInjectorBuilder instanceDeps" + bindsInstances);
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

      messager.printMessage(
          Kind.NOTE, TAG + " generateInjectorBuilder @BindsInstance " + key);
      utils.addSetMethod(
          types, elements, builderParentClassName, builderBuilder, key.getTypeName(), methodName, builderName);
    }
    messager.printMessage(Kind.NOTE, "generateInjectorBuilder, pos: 7");

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
    messager.printMessage(
        Kind.NOTE,
        "generateInjectorBuilder, return string: " + returnCodeBuilder.toString());
    buildMethodBuilder.addCode(returnCodeBuilder.toString(), componentClassName);
    builderBuilder.addMethod(buildMethodBuilder.build());

    injectorBuilder.addType(builderBuilder.build());
  }

  @Override
  protected Set<BindingKey> getAllCtorParameters() {
    Set<BindingKey> allParameters = new HashSet<>();
    if (parentEitherComponent != null) {
      allParameters.add(BindingKey.get(parentEitherComponent));
    }
    for (TypeElement dep : componentDependencies) {
      Preconditions.checkState(
          !utils.isEitherComponent(dep),
          "(sub)component " + dep + " found in component dependencies. ");
      allParameters.add(BindingKey.get(dep));
    }
    allParameters.addAll(bindsInstances);
    for (TypeElement typeElement : nonNullaryCtorModules) {
      allParameters.add(BindingKey.get(typeElement));
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
          messager.printMessage(Kind.NOTE, "generatePackagedInjectors: element: " + e);
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
            messager.printMessage(
                Kind.NOTE,
                TAG + ".generatePackagedInjectors: injection method for: " + injectedTypeElement);

            generateInjectionMethod(injectedTypeElement, "inject");

          } else if (utils.isProvisionMethodInInjector(method)) {
            generateProvisionMethodIfNeeded(
                utils.getKeyProvidedByMethod(method));
          } else {
            // TODO: ignore known elements like builders.
            messager.printMessage(
                Kind.WARNING,
                String.format("Unknown element %s from injector %s.", method, eitherComponent));
          }
          return null;
        });

    messager.printMessage(
        Kind.NOTE,
        TAG + ".generatePackagedInjectors. packagedInjectorBuilders: " + packagedInjectorBuilders);

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

    // messager.printMessage(
    //     Kind.NOTE, "javaFile for: " + builder.build() + "\n" + javaFile.toString());

    //       messager.printMessage(Kind.NOTE, String.format("java file: %s", javaFile));
    //       try {
    //         // javaFile.writeTo(processingEnv.getFiler());
    //       } catch (IOException e) {
    //         Throwables.propagate(e);
    //       }
  }

  @Override
  protected void addNewStatementToMethodSpec(
      MethodSpec.Builder methodSpecBuilder, DependencyInfo dependencyInfo, String newVarName) {
  ExecutableElement provisionMethodElement = dependencyInfo.getProvisionMethodElement();
    // if (provisionMethodElement != null && utils.isBindsMethod(provisionMethodElement)) {
    //   return generateProvisionMethodAndReturnCallingString(
    //       Iterables.getOnlyElement(dependencyInfo.getDependencies()));
    // } else {
    //   return generateStringCallingProxyProvisionMethod(dependencyInfo);
    // }
  }

  private String generateStringCallingProxyProvisionMethod(DependencyInfo dependencyInfo) {
    ExecutableElement provisionMethodElement = dependencyInfo.getProvisionMethodElement();
    Preconditions.checkArgument(
        provisionMethodElement == null || !utils.isAbstract(provisionMethodElement),
        "Cannot handle abstract method: " + dependencyInfo);
    boolean isModuleMethod =
        dependencyInfo.getDependencySourceType().equals(DependencySourceType.MODULE);
    boolean isCtorInjectedClass =
        dependencyInfo.getDependencySourceType().equals(DependencySourceType.CTOR_INJECTED_CLASS);
    Preconditions.checkArgument(
        isModuleMethod || isCtorInjectedClass,
        "unexpected DependencySourceType for: " + dependencyInfo);

    TypeElement sourceClassElement = dependencyInfo.getSourceClassElement();
    String packageString = utils.getPackageString(sourceClassElement);
    ClassName proxyClassName = ClassName.get(packageString, TIGER_PROXY_NAME);
    generateGetProxyMethodIfNeeded(packageString);
    StringBuilder builder = new StringBuilder();
    builder.append(utils.getGetMethodName(proxyClassName)).append("().");
    if (isModuleMethod) {
      builder.append(utils.getMethodNameCallingMethod(sourceClassElement, provisionMethodElement));
    } else {
      builder.append(utils.getGetMethodName(sourceClassElement));
    }
    builder.append("(");
    if (isModuleMethod && !utils.isStatic(provisionMethodElement)) {
      if (nonNullaryCtorModules.contains(sourceClassElement)) {
        builder.append(utils.getSourceCodeName(sourceClassElement));
      } else {
        generateGetTypeMethodIfNeeded(sourceClassElement);
        builder.append(utils.getGetMethodName(sourceClassElement)).append("()");
      }
      builder.append(", ");
    }
    List<BindingKey> parameters =
        isModuleMethod
            ? utils.getDependenciesFromExecutableElement(provisionMethodElement)
            : utils.getCtorDependencies(
                dependencies, dependencyInfo.getDependant());
    for (BindingKey dependentKey : parameters) {
      generateProvisionMethodAndAppendAsParameter(dependentKey, builder);
    }
    if (builder.substring(builder.length() - 2).equals(", ")) {
      builder.delete(builder.length() - 2, builder.length());
    }
    builder.append(")");

    return builder.toString();
  }

  protected String generateStringCallingProxySetFieldMethod(
      TypeElement cls, String clsName, VariableElement field, String fieldName) {
    String packageString = utils.getPackageString(cls);
    ClassName proxyClassName = ClassName.get(packageString, TIGER_PROXY_NAME);
    generateGetProxyMethodIfNeeded(packageString);
    StringBuilder builder = new StringBuilder();
    builder
        .append(utils.getGetMethodName(proxyClassName))
        .append("().")
        .append(utils.getMethodNameSettingField(cls, field))
        .append("(")
        .append(clsName)
        .append(", ")
        .append(fieldName)
        .append(")");

    return builder.toString();
  }

  protected String generateStringCallingProxyInjectionMethod(
      TypeElement cls, String clsName, ExecutableElement method, String... parameterNames) {
    String packageString = utils.getPackageString(cls);
    ClassName proxyClassName = ClassName.get(packageString, TIGER_PROXY_NAME);
    generateGetProxyMethodIfNeeded(packageString);
    StringBuilder builder = new StringBuilder();
    builder
        .append(utils.getGetMethodName(proxyClassName))
        .append("().")
        .append(utils.getMethodNameCallingMethod(cls, method))
        .append("(")
        .append(clsName);
    for (String parameterName : parameterNames) {
      builder.append(", ").append(parameterName);
    }
    builder.append(")");

    return builder.toString();
  }

}
