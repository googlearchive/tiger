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

import com.google.common.base.Optional;
import com.google.common.base.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import dagger.Lazy;
import dagger.MapKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Generated;
import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * `Shared function between hub and packaged injector of tiger 3. Below might be obsoleted and need
 * review.
 *
 * <p>TODO: handle generic everywhere properly without assuming it is not generic, maybe using
 * {@link com.squareup.javapoet.TypeName} always, with utilities to convert it back to {@link
 * javax.lang.model.element.Element} and {@link javax.lang.model.type.TypeMirror}.
 *
 * <p>Generates packaged injectors, multi-binding injectors and top level injectors. The former is
 * to get around accessibility limitation. The second is unscoped injector dedicated for
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
 * in context is returned. But the top level provision method will construct the component by its
 * ctor.
 *
 * <p>TODO: revisit all the asMemberOf to make sure only direct container is used.
 */
abstract class GeneralInjectorGenerator {
  private static final String TAG = "GeneralInjectorGenerator";

  /**
   * Used for value of @Generated(). It starts with "dagger." so that it will be exempted from
   * strict java deps check. TODO(freeman): change it to tiger.
   */
  private static final String GENERATOR_NAME = "dagger.GeneralInjectorGenerator";

  private static final String INJECT_METHOD_NAME = "inject";
  protected final String TIGER_PROXY_NAME = "TigerProxy";

  private static final boolean LOG_PROVISION_METHOD_ENABLED = false;
  protected static String LOCK_HOLDER_PACKAGE_STRING = "lock.holder";
  protected static String LOCK_HOLDER_CLASS_STRING = "LockHolder";
  protected static String LOCK_HOLDER_FIELD_STRIN = "theLock";

  static final String MULTI_BINDING_INJECTOR_NAME = "MultiBindingInjector";
  protected static final String TOP_LEVEL_INJECTOR_FIELD = "topLevelInjector";
  // Refers the packaged injector for parent scope.
  private static final String CONTAINING_PACKAGED_INJECTOR_FIELD = "containingPackagedInjector";
  private static final String UNSCOPED_SUFFIX = "_unscoped";

  // This does not include key for injected class, but does include its injected memebers.
  protected SetMultimap<BindingKey, DependencyInfo> dependencies;
  protected Set<TypeElement> componentDependencies;
  protected final Utils utils;

  /**
   * Includes multi-binding package. We use name instead of key because {@link
   * PackagedInjectorGenerator} will create local and global version for public types provided.
   */
  protected final Set<String> generatedBindings = new HashSet<>();

  // From packaged injector to spec builder.
  protected final Map<ClassName, Builder> packagedInjectorBuilders = Maps.newHashMap();

  // From packaged injector to injected ClassName.
  private final SetMultimap<ClassName, ClassName> injectedClassNamesForPackagedInjector =
      HashMultimap.create();

  protected final ProcessingEnvironment processingEnv;
  protected final Messager messager;
  protected final Elements elements;
  protected final Types types;
  protected Builder injectorBuilder;
  private final Set<String> proxiesWhoseGetterGenerated = new HashSet<>();
  private final Set<ClassName> typesWhoseGetterGenerated = new HashSet<>();
  private final Set<String> fieldsGenerated = new HashSet<>();
  // method simple name and type.
  protected final Set<Pair<String, TypeName>> injectionMethodsDone = new HashSet<>();
  protected final Logger logger;
  protected Set<TypeElement> modules;
  protected Set<TypeElement> nonNullaryCtorModules;

  public GeneralInjectorGenerator(
      SetMultimap<BindingKey, DependencyInfo> dependencies,
      Set<TypeElement> modules,
      Set<TypeElement> componentDependencies,
      ProcessingEnvironment env,
      Utils utils) {
    this.processingEnv = env;
    this.messager = env.getMessager();
    this.elements = env.getElementUtils();
    this.types = env.getTypeUtils();
    this.dependencies = dependencies;
    this.componentDependencies = componentDependencies;
    this.utils = utils;
    logger = new Logger(messager, Kind.WARNING);
    this.modules = modules;
    nonNullaryCtorModules = utils.getNonNullaryCtorOnes(modules);
    logger.n("modules: \n%s\n nonnullaryctor: \n%s\n", modules, nonNullaryCtorModules);
  }

  /** This are for the class header */
  protected abstract String getPackageString();

  protected abstract String getInjectorSimpleName();
  // external dependencies
  protected abstract Set<TypeName> getSuperInterfaces();
  // dependency map
  protected abstract Set<BindingKey> getAllCtorParameters();
  // Those can be either provided(first) or injected(second) by this injector.
  protected abstract Pair<Set<BindingKey>, Set<BindingKey>> getProduced();

  protected abstract String getProvisionMethodName(BindingKey key);
  /** used for the part after "var = " */
  protected abstract void addNewStatementToMethodSpec(
      MethodSpec.Builder methodSpecBuilder, DependencyInfo dependencyInfo, String newVarName);
  // Do implementation specific stuff.
  protected abstract void doSpecific();

  public void generate() {
    injectorBuilder = createInjectorBuilder();
    // messager.printMessage(Kind.NOTE,
    // "generatedBindings: " + generatedBindings);
    // logger.n("" + keyToPackagedInjectorMap);
    generateCtor();
    generateProduced();
    doSpecific();

    // Write
    JavaFile javaFile = JavaFile.builder(getPackageString(), injectorBuilder.build()).build();

    try {
      // logger.n(
      //     " package:%s\n%s",
      //     getPackageString(),
      //     new StringBuilder().append(javaFile.toJavaFileObject().getCharContent(true)).toString());
      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      logger.e(e.toString());
    }
  }

  private void generateProduced() {
    for (BindingKey key : getProduced().first) {
      generateProvisionMethodIfNeeded(key);
    }
    for (BindingKey key : getProduced().second) {
      generateInjectionMethod(utils.getTypeElement(key), "inject");
    }
  }

  private void generateCtor() {
    // Ctor
    MethodSpec.Builder ctorBuilder = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

    ctorBuilder.addStatement(
        "$T.out.printf($S, $L)", ClassName.get(System.class), "This is tiger: %s\n", "this");

    // TODO: maybe remove this.
    // Ctor - ancester top level injectors.
    // CoreInjectorInfo tmp = coreInjectorInfo;
    // while (componentTree.get(tmp) != null) {
    //   tmp = componentTree.get(tmp);
    //   ClassName className = ClassName.get(topLevelPackageString,
    //       getTopLevelInjectorName(tmp, topLevelInjectorPrefix, topLevelInjectorSuffix));
    //   String sourceCodeName = utils.getSourceCodeName(className);
    //   injectorBuilder.addField(className, sourceCodeName);
    //   if (tmp.equals(componentTree.get(coreInjectorInfo))) {
    //     ctorBuilder.addStatement(
    //         "this.$L = $L", sourceCodeName, containingInjectorName);
    //   } else {
    //     ctorBuilder.addStatement(
    //         "this.$L = $L.$L", sourceCodeName, containingInjectorName, sourceCodeName);
    //   }
    // }

    /**
     * Ctor - Component dependencies, @BindsInstance and Passed modules. All sorted together so that
     * it is easier to implement {@link #generateGetSubcomponentMethod(ExecutableElement, Builder)}
     */
    Set<BindingKey> allParameters = getAllCtorParameters();

    for (BindingKey key : utils.sortBindingKeys(allParameters)) {
      TypeName typeName = key.getTypeName();
      String sourceCodeName = utils.getSourceCodeName(key);
      generateFieldIfNeeded(typeName, sourceCodeName);
      ctorBuilder
          .addParameter(typeName, sourceCodeName)
          .addStatement("this.$L = $L", sourceCodeName, sourceCodeName);
    }

    injectorBuilder.addMethod(ctorBuilder.build());
  }

  protected static String getPackageFromInjectorClassName(ClassName injectorClassName) {
    return injectorClassName.packageName();
  }

  protected Builder createInjectorBuilder() {
    String injectorSimpleName = getInjectorSimpleName();
    logger.n("generated component " + injectorSimpleName);

    Builder result =
        TypeSpec.classBuilder(injectorSimpleName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(
                AnnotationSpec.builder(Generated.class)
                    .addMember("value", "$S", GENERATOR_NAME)
                    .build());

    for (TypeName typeName : getSuperInterfaces()) {
      result.addSuperinterface(typeName);
    }
    return result;
  }

  /**
   * For all bindings but single contributor of a multi-binding, which is handled by {@link
   * #getPackagedInjectorNameForDependencyInfo(BindingKey, DependencyInfo)} . For generic binding,
   * package of referencing class has access to both raw type and parameter types, though the
   * provision method generated for it will be duplicated in each such package.
   */
  protected ClassName getInjectorNameFor(BindingKey key, TypeElement referencingClass) {
    ClassName result = null;
    TypeElement scope = null; // scopeCalculator.calculate(key);
    DependencyInfo dependencyInfo =
        Iterables.getFirst(utils.getDependencyInfo(dependencies, key), null);
    // logger.n("" + key + " dependencyInfo: "
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
      logger.n("not found for key: " + key);
      DependencyInfo genericDependencyInfo = utils.getDependencyInfoByGeneric(dependencies, key);
      if (genericDependencyInfo != null) {
        packageString = utils.getPackageString(referencingClass);
      } else {
        logger.e(String.format("Cannot resolve %s.", key));
      }
    }

    if (result == null) {
      String simpleName =
          isMultiBinding
              ? Utils.getMultiBindingInjectorSimpleName(scope)
              : Utils.getPackagedInjectorSimpleName(scope);
      result =
          ClassName.get(isMultiBinding ? "" /*topLevelPackageString*/ : packageString, simpleName);
    }

    return result;
  }

  private String getInjectorSimpleName(TypeElement eitherComponent) {
    return utils.getComponentImplementationSimpleNameFromInterface(eitherComponent);
  }

  /** From the given dependencyInfo contributes to given key. */
  protected ClassName getPackagedInjectorNameForDependencyInfo(
      BindingKey key, DependencyInfo dependencyInfo) {
    TypeElement scope = null; // scopeCalculator.calculate(key);
    return getPackagedInjectorNameForDependencyInfo(scope, dependencyInfo);
  }

  /** From the given dependencyInfo contributes to given key. */
  protected ClassName getPackagedInjectorNameForDependencyInfo(
      TypeElement scope, DependencyInfo dependencyInfo) {
    return ClassName.get(
        utils.getPackageString(dependencyInfo.getSourceClassElement()),
        Utils.getPackagedInjectorSimpleName(scope));
  }

  protected void generateProvisionMethodIfNeeded(BindingKey key) {
    // logger.n("key: %s", key.toString());
    // TODO: put all the dependency handling logic in one place
    Set<DependencyInfo> dependencyInfos = Utils.getDependencyInfosHandlingBox(dependencies, key);
    DependencyInfo dependencyInfo =
        dependencyInfos == null ? null : Iterables.getFirst(dependencyInfos, null);
    // TODO: handle boxing better.
    if (dependencyInfo != null) {
      key = dependencyInfo.getDependant();
    }
    if (!generatedBindings.add(getProvisionMethodName(key))) {
      return;
    }
    // logger.n("dI: " + dependencyInfo);

    // logger.n("DependencyInfo: " +
    // dependencyInfo);
    // logger.n("scope: " +
    // scopeCalculator.calculate(key));
    boolean scoped = utils.isScoped(dependencyInfo); // explicitScopes.contains(key);
    String suffix = scoped ? UNSCOPED_SUFFIX : "";
    /**
     * TODO: revist this and handle it in a consistent way with the ones below. This is related with
     * {@link Utils#getDependencyInfo(SetMultimap, BindingKey)}.
     */
    if (utils.isOptional(key)
        && utils.isBindsOptionalOf(utils.getDependencyInfo(dependencies, key))) {
      generateProvisionMethodForBindsOptionalOf(key, suffix);
    } else if (dependencyInfo != null) {
      switch (dependencyInfo.getType()) {
        case SET:
        case SET_VALUES:
          // TODO: revisit scoped
          // TODO: Multi-bindings are handled multiple time for each package if there more multiple
          // contributors from
          // that package. But that's fine because the nature of Set and Map.
          scoped = false;
          generateProvisionMethodForSet(key, "");
          break;
        case MAP:
          // TODO: refactor here and below.
          // TODO: revisit scoped
          scoped = false;
          generateProvisionMethodForMap(key, "");
          break;
        case UNIQUE:
          switch (dependencyInfo.getDependencySourceType()) {
            case MODULE:
              generateProvisionMethodFromModuleUniqueBinding(key, suffix);
              break;
            case CTOR_INJECTED_CLASS:
              generateProvisionMethodFromClass(key, suffix);
              break;
            case DAGGER_MEMBERS_INJECTOR:
              generateProvisionMethodForDaggerMembersInjector(key, suffix);
              break;
            case COMPONENT_DEPENDENCIES_METHOD:
              generateProvisionMethodFromComponentDependency(key);
              break;
            case BINDS_INTANCE:
            case COMPONENT_DEPENDENCIES_ITSELF:
              /** TODO: move hub related stuff away to {@link Tiger2InjectorGenerator} */
              generateProvisionMethodFromBindsInstance(key);
              break;
            case EITHER_COMPONENT:
              break;
            case EITHER_COMPONENT_BUILDER:
              generateProvisionMethodForEitherComponentBuilder(key);
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
    } else if (utils.isProviderOrLazy(key)) {
      generateProvisionMethodForProviderOrLazy(key, suffix);
    } else if (utils.isMap(key)) {
      Preconditions.checkState(
          utils.isMapWithBuiltinValueType(key), "Expect map with builtin type but got: " + key);
      generateProvisionMethodForMap(key, suffix);
    } else {
      logger.w("really!? we have handling generic.");
      logger.n("stack:");
      for (StackTraceElement e : new Exception("").getStackTrace()) {
        logger.n("%s", e);
      }
      logger.n("dependencies:\n%s", dependencies);
      DependencyInfo genericDependencyInfo = utils.getDependencyInfoByGeneric(dependencies, key);
      if (genericDependencyInfo != null) {
        if (genericDependencyInfo.getProvisionMethodElement() == null) {
          generateProvisionMethodFromClass(key, suffix);
        } else {
          logger.e(
              "Generic provision method not supported yet: %s -> %s", key, genericDependencyInfo);
        }
      } else {
        logger.e("Cannot resolve %s", key);
        // throw new RuntimeException(errorMessage);
      }
    }
    if (scoped) {
      generateFieldIfNeeded(key.getTypeName(), getFieldName(key));
      generateScopedProvisionMethod(injectorBuilder, key);
    }
  }

  protected void generateProvisionMethodFromComponentDependency(BindingKey key) {
    generateProvisionMethodForThoseFromTopLevel(key);
  }

  /**
   * for {@link DependencySourceType#COMPONENT_DEPENDENCIES_ITSELF}, {link {@link
   * DependencySourceType#COMPONENT_DEPENDENCIES_METHOD} and {@link
   * DependencySourceType#BINDS_INTANCE}, {@link DependencySourceType#EITHER_COMPONENT}, {@link
   * DependencySourceType#EITHER_COMPONENT_BUILDER}
   */
  protected void generateProvisionMethodForThoseFromTopLevel(BindingKey key) {
    DependencyInfo dependencyInfo =
        Iterables.getOnlyElement(Utils.getDependencyInfosHandlingBox(dependencies, key));
    DependencySourceType dependencySourceType = dependencyInfo.getDependencySourceType();

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key))
            .addModifiers(Modifier.PUBLIC)
            .returns(key.getTypeName());

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result", key.getTypeName());
    StringBuilder builder = new StringBuilder("result = ");
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
        builder.append(Utils.getSourceCodeNameHandlingBox(key, dependencies));
        break;
      case EITHER_COMPONENT:
        /** TODO: see {@link #generateGetSubcomponentMethod(ExecutableElement, Builder)} */
        break;
      case EITHER_COMPONENT_BUILDER:
        generateImplicitProvisionMethodForEitherComponentBuilder(
            injectorBuilder, utils.getTypeElement(dependencyInfo.getDependant()));
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

    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  protected void generateProvisionMethodFromBindsInstance(BindingKey key) {
    generateProvisionMethodForThoseFromTopLevel(key);
  }

  protected void generateProvisionMethodForBindsOptionalOf(BindingKey key, String suffix) {
    BindingKey elementKey = utils.getElementKeyForParameterizedBinding(key);
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, elementKey);
    boolean present = dependencyInfos != null;

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(key.getTypeName());
    onProvisionMethodStart(methodSpecBuilder, key);
    if (present) {
      methodSpecBuilder.addStatement("$T value", elementKey.getTypeName());
      DependencyInfo dependencyInfo = Iterables.getOnlyElement(dependencyInfos);
      generateProvisionMethodIfNeeded(elementKey);
      StringBuilder stringBuilder = new StringBuilder("value = ");
      addCallingProvisionMethod(stringBuilder, elementKey);
      methodSpecBuilder.addStatement(stringBuilder.toString());
      methodSpecBuilder.addStatement("return $T.of(value)", ClassName.get(Optional.class));

    } else {
      methodSpecBuilder.addStatement("return $T.absent()", ClassName.get(Optional.class));
    }
    onProvisionMethodEnd(methodSpecBuilder, key);

    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  protected void onProvisionMethodStart(MethodSpec.Builder methodSpecBuilder, BindingKey key) {
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

  protected void onProvisionMethodEnd(MethodSpec.Builder methodSpecBuilder, BindingKey key) {
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

  // TODO: Refactor, so far this happens to work for provision methods from component dependencies.
  protected void generateProvisionMethodFromModuleUniqueBinding(BindingKey key, String suffix) {
    // DependencyInfo dependencyInfo =
    //     Iterables.getOnlyElement(Utils.getDependencyInfosHandlingBox(dependencies, key));

    // TODO: this is a hack to make it build for same type bound in one package to different types.
    Set<DependencyInfo> dependencyInfos = Utils
        .getDependencyInfosHandlingBox(dependencies, key);
    if (dependencyInfos.size() > 1) {
      logger.w("multiple unique bindings found for key: %s, bindings: %s", key, dependencyInfos);
    }
    DependencyInfo dependencyInfo =
        Preconditions.checkNotNull(
            Iterables.getFirst(dependencyInfos, null), "binding not found for key " + key);

    generateProvisionMethodFromModuleBinding(dependencyInfo, suffix, "");
    // logger.n(String.format(
    // "generateUniqueTypeProvisionMethodFromModule: \n key: %s, \n injector: %s, \n method: %s.",
    // key, injectorClassName, methodSpecBuilder.build()));
  }

  /**
   * empty methodName for default behavior, otherwise for special case like multi-binding
   * contributors from{@link PackagedInjectorGenerator}}
   */
  protected void generateProvisionMethodFromModuleBinding(
      DependencyInfo dependencyInfo, String suffix, String methodName) {
    Preconditions.checkNotNull(dependencyInfo.getProvisionMethodElement());
    BindingKey key = dependencyInfo.getDependant();
    TypeMirror returnType = dependencyInfo.getProvisionMethodElement().getReturnType();
    BindingKey returnKey = BindingKey.get(returnType, key.getQualifier());
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(
            !methodName.isEmpty() ? methodName : getProvisionMethodName(returnKey) + suffix);
    methodSpecBuilder
        .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
        .returns(TypeName.get(returnType));

    onProvisionMethodStart(methodSpecBuilder, key);

    /** TODO: unitfy this with {@link #generateProvisionMethodFromClass(BindingKey, String)} */
    methodSpecBuilder.addStatement("$T result", returnKey.getTypeName());
    if (methodName.isEmpty()) {
      addNewStatementToMethodSpec(methodSpecBuilder, dependencyInfo, "result");
    } else {
      addNewStatementToMethodSpecByModuleOrCtor(methodSpecBuilder, dependencyInfo, "result");
    }
    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);
    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  protected void generateGetProxyMethodIfNeeded(String packageString) {
    if (!proxiesWhoseGetterGenerated.add(packageString)) {
      return;
    }
    ClassName proxyClassName = ClassName.get(packageString, TIGER_PROXY_NAME);
    generateGetTypeMethod(proxyClassName);
  }

  /** generate a getXXX() that returns xxx field, initialize it if needed. */
  protected void generateGetTypeMethod(ClassName proxyClassName) {
    String proxySourceName = Utils.getSourceCodeName(proxyClassName);
    generateFieldIfNeeded(proxyClassName, proxySourceName);
    injectorBuilder.addMethod(
        MethodSpec.methodBuilder(utils.getGetMethodName(proxyClassName))
            .addModifiers(Modifier.PRIVATE)
            .returns(proxyClassName)
            .beginControlFlow("if ($L == null)", proxySourceName)
            .addStatement("$L = new $T()", proxySourceName, proxyClassName)
            .endControlFlow()
            .addStatement("return $L", proxySourceName)
            .build());
  }

  protected void generateFieldIfNeeded(TypeName typeName, String fieldName) {
    if (!fieldsGenerated.add(fieldName)) {
      return;
    }
    injectorBuilder.addField(typeName, fieldName, Modifier.PRIVATE);
  }

  protected void generateGetTypeMethodIfNeeded(TypeElement module) {
    generateGetTypeMethodIfNeeded(ClassName.get(module));
  }

  protected void generateGetTypeMethodIfNeeded(ClassName className) {
    if (!typesWhoseGetterGenerated.add(className)) {
      return;
    }
    generateGetTypeMethod(className);
  }

  /**
   * For key like javax.inject.Provider<Foo> and dagger.Lazy<Foo>. Qualifier, if presented, will
   * also apply to element binding.
   */
  protected void generateProvisionMethodForProviderOrLazy(BindingKey key, String suffix) {
    // logger.n(String.format(
    // "generateProvisionMethodForProviderOrLazy: key %s, referencingClass: %s, suffix : %s.", key,
    // referencingClass, suffix));

    TypeSpec anonymousTypeSpec = createAnonymousBuiltinTypeForUniqueBinding(key);
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(key.getTypeName());

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result = $L", key.getTypeName(), anonymousTypeSpec);

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);

    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  protected TypeSpec createAnonymousBuiltinTypeForUniqueBinding(BindingKey key) {
    return createAnonymousBuiltinType(key, null);
  }

  protected TypeSpec createAnonymousBuiltinTypeForMultiBinding(
      BindingKey key, DependencyInfo dependency) {
    return createAnonymousBuiltinType(key, dependency);
  }

  /**
   * Generate for either unique a binding or a contributor to a multi-binding. DependencyInfo is
   * null for unique one and non-null or multi-binding one. ReferencingClass is the opposite. For
   * multi-binding, referencing class is the module in dependency. Scope is null for unique binding.
   */
  protected TypeSpec createAnonymousBuiltinType(
      BindingKey key, @Nullable DependencyInfo dependencyInfo) {
    Preconditions.checkArgument(key.getTypeName() instanceof ParameterizedTypeName);
    boolean isMultiBinding = dependencyInfo != null;
    TypeName rawTypeName = ((ParameterizedTypeName) key.getTypeName()).rawType;
    Preconditions.checkArgument(
        utils.isProviderOrLazy(key),
        String.format("Built-in binding expected(Provider or Lazy), but get %s", key));
    boolean isLazy = rawTypeName.equals(ClassName.get(Lazy.class));
    BindingKey elementKey = utils.getElementKeyForParameterizedBinding(key);
    Preconditions.checkNotNull(elementKey);
    if (!isMultiBinding) {
      generateProvisionMethodIfNeeded(elementKey);
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
      Set<DependencyInfo> dIs = Utils.getDependencyInfosHandlingBox(dependencies, elementKey);
      if (dIs != null) {
        elementKey =
            Preconditions.checkNotNull(
                    Iterables.getFirst(dIs, null), "key: " + elementKey + " dI: " + dIs)
                .getDependant();
      }
      builderForGet.addStatement("var = $N()", getProvisionMethodName(elementKey));
    } else {
      /**
       * TODO: revisit the logic here, current, for Provide, Lazy and Optional, the key != {@link
       * DependencyInfo#getDependant()}.
       */
      addNewStatementToMethodSpec(builderForGet, dependencyInfo, "var");
    }
    if (isLazy) {
      builderForGet.endControlFlow();
    }
    builderForGet.addStatement("return var");

    return TypeSpec.anonymousClassBuilder("")
        .addSuperinterface(key.getTypeName())
        .addMethod(builderForGet.build())
        .build();
  }

  protected void generateScopedProvisionMethod(Builder componentSpecBuilder, BindingKey key) {
    MethodSpec.Builder builder =
        MethodSpec.methodBuilder(getProvisionMethodName(key))
            .returns(key.getTypeName())
            .addModifiers(Modifier.PUBLIC);
    builder
        .addStatement("$T result = $N", key.getTypeName().box(), getFieldName(key))
        .beginControlFlow("if (result == null)")
        .addStatement("result = $N", getFieldName(key))
        .beginControlFlow("if (result == null)")
        .addStatement(
            "result = $L = $L()", getFieldName(key), getProvisionMethodName(key) + UNSCOPED_SUFFIX)
        .endControlFlow() // if
        .endControlFlow() // if
        .addStatement("return result");
    componentSpecBuilder.addMethod(builder.build());
  }

  protected String getFieldName(BindingKey key) {
    return Utils.getSourceCodeNameHandlingBox(key, dependencies);
  }

  /** TODO: support set of builtin types. */
  protected void generateProvisionMethodForSet(BindingKey key, String suffix) {
    // logger.n("" + key +
    // " PackagedInjector: "
    // + getInjectorFor(key, referencingClass) + " SpecBuilder: " + componentSpecBuilder);
    ParameterizedTypeName type = (ParameterizedTypeName) key.getTypeName();
    Preconditions.checkArgument(type.rawType.equals(ClassName.get(Set.class)));
    TypeName elementType = Iterables.getOnlyElement(type.typeArguments);

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(type);

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result = new $T<>()", type, HashSet.class);
    generateSetContributors(key, methodSpecBuilder);

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);
    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  protected void generateSetContributors(BindingKey key, MethodSpec.Builder methodSpecBuilder) {
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, key);
    for (DependencyInfo dependencyInfo : dependencyInfos) {
      // logger.n("for %s from"
      // +
      // " %s", key, packageToDependencyInfoMap.get(pkg)));

      ExecutableElement provisionMethodElement = dependencyInfo.getProvisionMethodElement();
      boolean isSetValues = dependencyInfo.getType().equals(SET_VALUES);
      if (utils.isMultibindsMethod(provisionMethodElement)) {
        continue;
      }
      methodSpecBuilder.beginControlFlow("");
      TypeName contributorType =
          TypeName.get(dependencyInfo.getProvisionMethodElement().getReturnType());
      methodSpecBuilder.addStatement("$T contributor", contributorType);
      addNewStatementToMethodSpec(methodSpecBuilder, dependencyInfo, "contributor");
      if (dependencyInfo.getType().equals(SET)) {
        methodSpecBuilder.addStatement("result.add(contributor)");
      } else {
        Preconditions.checkState(dependencyInfo.getType().equals(SET_VALUES));
        methodSpecBuilder.addStatement("result.addAll(contributor)");
      }
      methodSpecBuilder.endControlFlow();
    }
  }

  protected void generateProvisionMethodForMap(final BindingKey key, String suffix) {
    // logger.n("" + key +
    // " PackagedInjector: "
    // + getInjectorFor(key, referencingClass) + " SpecBuilder: " + componentSpecBuilder);
    ParameterizedTypeName returnType = (ParameterizedTypeName) key.getTypeName();
    Preconditions.checkArgument(returnType.rawType.equals(ClassName.get(Map.class)));

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(returnType);

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result = new $T<>()", returnType, HashMap.class);
    generateMapContributors(key, returnType, methodSpecBuilder);
    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);
    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  protected void addNewStatementToMethodSpecByModuleOrCtor(
      MethodSpec.Builder methodSpecBuilder, DependencyInfo dependencyInfo, String newVarName) {
    // logger.n(" dependencyInfo : " + dependencyInfo);
    ExecutableElement provisionMethodElement = dependencyInfo.getProvisionMethodElement();
    if (provisionMethodElement == null) {
      StringBuilder builder = new StringBuilder("$L = new $T(");
      List<BindingKey> dependenciesFromExecutableElement =
          utils.getDependenciesFromExecutableElement(
              utils.findInjectedCtor(utils.getTypeElement(dependencyInfo.getDependant())));
      for (BindingKey dependentKey : dependenciesFromExecutableElement) {
        generateProvisionMethodAndAppendAsParameter(dependentKey, builder);
      }
      if (builder.substring(builder.length() - 2).equals(", ")) {
        builder.delete(builder.length() - 2, builder.length());
      }
      builder.append(")");
      methodSpecBuilder.addStatement(
          builder.toString(), newVarName, ClassName.get(dependencyInfo.getSourceClassElement()));
    } else if (utils.isBindsMethod(provisionMethodElement)) {
      // for @Binds
      StringBuilder stringBuilder = new StringBuilder();
      addCallingProvisionMethod(
          stringBuilder, Iterables.getOnlyElement(dependencyInfo.getDependencies()));
      methodSpecBuilder.addStatement("$L = $L", newVarName, stringBuilder.toString());
    } else {
      boolean isStaticMethod =
          dependencyInfo.getProvisionMethodElement().getModifiers().contains(Modifier.STATIC);
      StringBuilder builder = new StringBuilder("$L = ");
      if (!utils.isStatic(provisionMethodElement)) {
        TypeElement sourceClassElement = dependencyInfo.getSourceClassElement();
        if (nonNullaryCtorModules.contains(sourceClassElement)) {
          builder.append(utils.getSourceCodeName(sourceClassElement));
        } else {
          generateGetTypeMethodIfNeeded(sourceClassElement);
          builder.append(utils.getGetMethodName(sourceClassElement)).append("()");
        }
      } else {
        builder.append(utils.getQualifiedName(dependencyInfo.getSourceClassElement()));
      }
      builder.append(".$N(");
      List<BindingKey> dependenciesFromExecutableElement =
          utils.getDependenciesFromExecutableElement(provisionMethodElement);
      for (BindingKey dependentKey : dependenciesFromExecutableElement) {
        generateProvisionMethodAndAppendAsParameter(dependentKey, builder);
      }
      if (builder.substring(builder.length() - 2).equals(", ")) {
        builder.delete(builder.length() - 2, builder.length());
      }
      builder.append(")");

      methodSpecBuilder.addStatement(
          builder.toString(), newVarName, provisionMethodElement.getSimpleName());
    }
  }

  protected void generateMapContributors(
      BindingKey key, ParameterizedTypeName returnType, MethodSpec.Builder methodSpecBuilder) {
    Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, key);

    // TODO: remove this hack
    if (dependencyInfos == null) {
      dependencyInfos = new HashSet<>();
      logger.w("no dI for key: " + key);
    }

    Preconditions.checkNotNull(
        dependencyInfos, String.format("dependencyInfo not found for key: %s", key));
    TypeName mapKeyType = returnType.typeArguments.get(0);
    TypeName mapValueType = returnType.typeArguments.get(1);
    BindingKey mapValueKey = BindingKey.get(mapValueType);
    methodSpecBuilder.addStatement("$T mapKey", mapKeyType);
    methodSpecBuilder.addStatement("$T mapValue", mapValueType);
    for (DependencyInfo di : dependencyInfos) {
      if (utils.isMultibindsMethod(di.getProvisionMethodElement())) {
        continue;
      }

      AnnotationMirror mapKeyMirror =
          Utils.getAnnotationMirrorWithMetaAnnotation(di.getProvisionMethodElement(), MapKey.class);
      AnnotationValue unwrapValueAnnotationValue =
          Utils.getAnnotationValue(elements, mapKeyMirror, "unwrapValue");
      if (unwrapValueAnnotationValue != null
          && !((boolean) unwrapValueAnnotationValue.getValue())) {
        logger.e("unwrapValue = false not supported yet. Consider using set binding.");
        return;
      }
      AnnotationValue mapKey = Utils.getAnnotationValue(elements, mapKeyMirror, "value");
      logger.l(Kind.NOTE, "mapKey: %s", mapKey.toString());
      methodSpecBuilder.addStatement("mapKey = ($T) $L", mapKeyType, mapKey);
      if (utils.isMapWithBuiltinValueType(key)) {
        methodSpecBuilder.addStatement(
            "mapValue = $L", createAnonymousBuiltinTypeForMultiBinding(mapValueKey, di));
      } else {
        addNewStatementToMethodSpec(methodSpecBuilder, di, "mapValue");
      }
      methodSpecBuilder.addStatement("result.put(mapKey, mapValue)");
    }
  }

  protected void generateInjectionMethod(BindingKey key) {
    generateInjectionMethod(utils.getClassFromKey(key), "inject");
  }

  protected void generateInjectionMethod(TypeElement cls, String methodName) {

    // logger.n("cls: %s, injector: %s, method: %s", cls,
    // packagedInjectorClassName, methodName));
    if (!injectionMethodsDone.add(Pair.of(methodName, TypeName.get(cls.asType())))) {
      logger.w("duplicate injection method: " + methodName + " for type: " + cls);
      return;
    }

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(ClassName.get(cls), "arg");
    generateInjectionMethodBody(cls, methodSpecBuilder);

    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  // TODO: move this to PackagedInjectorGenerator.
  protected void generateInjectionMethodBody(
      TypeElement cls, MethodSpec.Builder methodSpecBuilder) {
    // Inject closest ancestor first.
    TypeElement clsClosestInjectAncestor = utils.getClosestInjectedAncestor(cls);
    if (clsClosestInjectAncestor != null) {
      if (clsClosestInjectAncestor.getModifiers().contains(Modifier.PUBLIC)) {
        String packageString = utils.getPackageString(cls);
        ClassName hub = ClassName.get(packageString, PackagedHubInterfaceGenerator.HUB_INTERFACE);
        methodSpecBuilder.addStatement(
            "$L.inject(($T) arg)",
            utils.getSourceCodeName(hub),
            ClassName.get(clsClosestInjectAncestor));
      } else {
        methodSpecBuilder.addStatement("inject(($T) arg)", ClassName.get(clsClosestInjectAncestor));
      }
    }

    for (VariableElement field : utils.getInjectedFields(cls, processingEnv)) {
      // logger.n("field: " + field);
      TypeMirror fieldType = field.asType();
      AnnotationMirror fieldQualifier = Utils.getQualifier(field);
      BindingKey fieldKey = BindingKey.get(fieldType, fieldQualifier);
      generateProvisionMethodIfNeeded(fieldKey);
      StringBuilder stringBuilder =
          new StringBuilder("arg.").append(field.getSimpleName()).append(" = ");
      addCallingProvisionMethod(stringBuilder, fieldKey);
      methodSpecBuilder.addStatement(stringBuilder.toString());
    }

    for (ExecutableElement method : utils.getInjectedMethods(cls, processingEnv)) {
      StringBuilder builder = new StringBuilder("arg.").append(method.getSimpleName()).append("(");
      List<BindingKey> methodArgs = utils.getDependenciesFromExecutableElement(method);
      if (methodArgs.size() > 0) {
        for (BindingKey dependentKey : methodArgs) {
          addCallingProvisionMethod(builder, dependentKey);
          builder.append(", ");
        }
        builder.delete(builder.length() - 2, builder.length());
      }
      builder.append(")");
      methodSpecBuilder.addStatement(builder.toString());
    }
  }

  /** Adds "getxxx()" to the builder. */
  protected void addCallingProvisionMethod(StringBuilder stringBuilder, BindingKey key) {
    generateProvisionMethodIfNeeded(key);
    stringBuilder.append(getProvisionMethodName(key)).append("()");
  }

  /** Generic is handled. */
  protected void generateProvisionMethodFromClass(BindingKey key, String suffix) {
    // logger.n("key: " + key + " referencingClass: " +
    // referencingClass);

    TypeElement cls = utils.getClassFromKey(key);
    DeclaredType clsType = (DeclaredType) utils.getTypeFromKey(key);

    ExecutableElement ctor = utils.findInjectedCtor(cls);
    Preconditions.checkNotNull(ctor, String.format("Did not find ctor for %s", cls));
    ExecutableType ctorType = (ExecutableType) types.asMemberOf(clsType, ctor);
    List<BindingKey> dependencyKeys = utils.getDependenciesFromMethod(ctorType, ctor);

    // TODO: clean this.
    // if (key.getTypeName() instanceof ParameterizedTypeName) {
    //   logger.n("be here :" + key);
    //   List<BindingKey> specializedKeys = new ArrayList<>();
    //   Map<TypeVariableName, TypeName> map =
    //       utils.getMapFromTypeVariableToSpecialized((ParameterizedTypeName) key.getTypeName(),
    //           (ParameterizedTypeName) TypeName.get(cls.asType()));
    //   for (BindingKey k : dependencyKeys) {
    //     specializedKeys.add(utils.specializeIfNeeded(k, map));
    //   }
    //   dependencyKeys = specializedKeys;
    // }

    // logger.n("dependencyKeys: " +
    // dependencyKeys);

    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix);
    methodSpecBuilder
        .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
        .returns(key.getTypeName());

    onProvisionMethodStart(methodSpecBuilder, key);

    methodSpecBuilder.addStatement("$T result", key.getTypeName());
    addNewStatementToMethodSpec(
        methodSpecBuilder, Iterables.getOnlyElement(dependencies.get(key)), "result");

    if (!utils.isNotSpecializedGeneric(cls.asType())
        && utils.hasInjectedFieldsOrMethodsRecursively(cls, processingEnv)) {
      // logger.n("hasInjected");
      generateInjectionMethod(key);
      methodSpecBuilder.addStatement("inject(result)");
    }

    methodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(methodSpecBuilder, key);
    injectorBuilder.addMethod(methodSpecBuilder.build());
  }

  protected void generateProvisionMethodForDaggerMembersInjector(BindingKey key, String suffix) {
    // logger.n("key: " + key + " referencingClass: " +
    // referencingClass);

    BindingKey childKey = utils.getElementKeyForParameterizedBinding(key);

    TypeName childTypeName = childKey.getTypeName();
    String parameterSourceCodeName = Utils.getSourceCodeName(childTypeName);
    MethodSpec.Builder injectMethodSpecBuilder =
        MethodSpec.methodBuilder("injectMembers")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(childTypeName, parameterSourceCodeName)
            .addAnnotation(Override.class);

    generateInjectionMethod(childKey);
    injectMethodSpecBuilder.addStatement("$L($L)", "inject", parameterSourceCodeName);

    TypeSpec injectorType =
        TypeSpec.anonymousClassBuilder("")
            .addSuperinterface(key.getTypeName())
            .addMethod(injectMethodSpecBuilder.build())
            .build();

    MethodSpec.Builder provisionMethodSpecBuilder =
        MethodSpec.methodBuilder(getProvisionMethodName(key) + suffix)
            .addModifiers(suffix.isEmpty() ? Modifier.PUBLIC : Modifier.PRIVATE)
            .returns(key.getTypeName());

    onProvisionMethodStart(provisionMethodSpecBuilder, key);

    provisionMethodSpecBuilder.addStatement("$T result = $L", key.getTypeName(), injectorType);

    provisionMethodSpecBuilder.addStatement("return result");
    onProvisionMethodEnd(provisionMethodSpecBuilder, key);
    // logger.n("" + provisionMethodSpecBuilder.build());
    injectorBuilder.addMethod(provisionMethodSpecBuilder.build());
  }

  protected void generateProvisionMethodForEitherComponentBuilder(BindingKey key) {
    // The top level injector must be the one that provides the wanted builder.
    generateProvisionMethodForThoseFromTopLevel(key);
  }

  protected void generateProvisionMethodAndAppendAsParameter(
      BindingKey key, StringBuilder builder) {
    builder.append(generateProvisionMethodAndReturnCallingString(key)).append(", ");
  }

  protected String generateProvisionMethodAndReturnCallingString(BindingKey key) {
    generateProvisionMethodIfNeeded(key);
    return getProvisionMethodName(key) + "()";
  }

  // Returns if the element is a method irrelevant to injection.
  protected boolean isIrrelevantMethodInInjector(Element element) {
    return !Utils.isMethod(element) || !element.getModifiers().contains(Modifier.ABSTRACT);
  }

  protected boolean isEitherComponentBuilderProvsionMethodProvidedByModule(Element method) {
    TypeElement element = Utils.getReturnTypeElement((ExecutableElement) method);
    return utils.isEitherComponentBuilderProvisionMethod(method)
        && Iterables.getOnlyElement(utils.getDependencyInfo(dependencies, BindingKey.get(element)))
            .getDependencySourceType()
            .equals(DependencySourceType.MODULE);
  }

  /**
   * (sub)component builder provision method that has not been explicitly specific in the parent
   * (sub)component.
   */
  protected void generateImplicitProvisionMethodForEitherComponentBuilder(
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

  protected ClassName getClassNameForEitherComponentBuilder(TypeElement componentBuilder) {
    TypeElement enclosingElement = (TypeElement) componentBuilder.getEnclosingElement();

    return ClassName.get(
        utils.getPackageString(enclosingElement),
        getInjectorSimpleName(enclosingElement),
        componentBuilder.getSimpleName().toString());
  }

  protected void generateGetSubcomponentMethod(ExecutableElement method, Builder injectorBuilder) {
    TypeElement returnType = Utils.getReturnTypeElement(method);
    logger.n("returnType: " + returnType + " method: " + method);

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
    List<BindingKey> sortedParams = utils.sortBindingKeys(keyNameMap.keySet());
    for (BindingKey key : sortedParams) {
      String name = keyNameMap.get(key);
      returnCodeBuilder.append(name).append(", ");
    }

    int size = returnCodeBuilder.length();
    returnCodeBuilder.delete(size - 2, size);
    returnCodeBuilder.append(");");
    buildMethodBuilder.addCode(
        returnCodeBuilder.toString(),
        ClassName.get(utils.getPackageString(returnType), getInjectorSimpleName(returnType)));

    injectorBuilder.addMethod(buildMethodBuilder.build());
  }

  protected void generateExplicitProvisionMethodForEitherComponentBuilder(
      ExecutableElement method, Builder injectorBuilder) {
    TypeElement returnType = Utils.getReturnTypeElement(method);
    logger.n("" + returnType + " method:  " + method);

    Preconditions.checkArgument(
        Utils.getQualifier(returnType) == null,
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
    return null; // ClassName.get(topLevelPackageString, getTopLevelInjectorName(component,
    // topLevelInjectorPrefix, topLevelInjectorSuffix), "Builder");
  }

  protected boolean isInjectorOfScope(ClassName injectorClassName, TypeElement scope) {
    return injectorClassName
        .simpleName()
        .contains(scope.getQualifiedName().toString().replace(".", "_"));
  }

  public String getTopLevelInjectorName(
      CoreInjectorInfo component, String topLevelInjectorPrefix, String topLevelInjectorSuffix) {
    return null; // this.topLevelInjectorPrefix + component.getName() + this.topLevelInjectorSuffix;
  }
}
