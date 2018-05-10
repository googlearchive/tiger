package tiger;

import com.google.common.base.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec.Builder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * This collect all the modules and injected classes therefore MUST be called after the other
 * processing rounds that could generate those classes. (probably not the same round). We collect so
 * that multiple building targets in the same package can generate full and identical result.
 *
 * <p>Packaged injectors are straight. They just reflect what the package provides. All that missed
 * go to hub.
 *
 * <p>Scope is handled in package injectors, hub can not handle non public types. Therefore let
 * packaged injectors handle it consistently.
 *
 * <p>Multi-binding, builtin and boxing are handled in hub.
 */
public class PackagedInjectorGenerator extends GeneralInjectorGenerator {
  private static final String TAG = "PackagedInjectorGenerator";

  public static final String TIGER_PAKCAGED_INJECTOR = "TigerPackagedInjector";
  // Hub is the toplevel-injector/dagger-(sub)component generated.
  private final Types types;
  private final Elements elements;
  private final Messager messager;
  private final DependencyCollector dependencyCollector;
  private ProcessingEnvironment processingEnv;
  private RoundEnvironment roundEnvironment;
  private Utils utils;
  private Set<TypeElement> ctorInjectedClasses = new HashSet<>();
  private Set<TypeElement> injectedClasses = new HashSet<>();
  private PackageElement packageElement;
  private Builder injectorBuilder;
  private String packageString;
  private final Logger logger;
  // those that does not include from hub interface.
  private SetMultimap<BindingKey, DependencyInfo> internalDependencyMap;
  private Set<BindingKey> hubInterfaceKeys;

  /**
   * TODO: assuming that all the multi-bindings in a package are always used together. TODO: Support
   * multiple public unique bindings. TODO: Support multiple local unique bindings. (maybe not)
   */
    public PackagedInjectorGenerator(
      String packageString,
      ProcessingEnvironment processingEnv,
      RoundEnvironment roundEnvironment,
      Utils utils) {
    super(HashMultimap.create(), new HashSet<>(), new HashSet<>(), processingEnv, utils);
    this.packageString = packageString;
    types = processingEnv.getTypeUtils();
    elements = processingEnv.getElementUtils();
    messager = processingEnv.getMessager();
    this.processingEnv = processingEnv;
    this.roundEnvironment = roundEnvironment;
    this.utils = utils;
    logger = new Logger(messager, Kind.WARNING);
    logger.n("in ctor");
    dependencyCollector = DependencyCollector.getInstance(processingEnv, utils);
    packageElement = elements.getPackageElement(packageString);
    initialize();
    nonNullaryCtorModules = utils.getNonNullaryCtorOnes(modules);
    logger.n(
        "package: %s\nmodules: \n%s\nctorInjectedClasses: \n%s\ninjectedClasses: \n%s\nnonnullaryctor: \n%s\n",
        packageString, modules, ctorInjectedClasses, injectedClasses, nonNullaryCtorModules);
  }

  private void initialize() {
    collectModulesAndInjected();
    dependencies = collectDependencyMap();
    logger.n("dependencies: %s", dependencies);
    logger.n("internalDependencies: %s", internalDependencyMap);

  }

  private SetMultimap<BindingKey, DependencyInfo> collectDependencyMap() {
    SetMultimap<BindingKey, DependencyInfo> result = HashMultimap.create();
    internalDependencyMap = collectInternalDependencyMap();
    result.putAll(internalDependencyMap);
    // Overwrite the internal ones.
    SetMultimap<BindingKey, DependencyInfo> fromDep =
        dependencyCollector.collectFromComponentDependency(
            Preconditions.checkNotNull(
                utils.getTypeElement(
                    BindingKey.get(
                        ClassName.get(packageString, PackagedHubInterfaceGenerator.HUB_INTERFACE))),
                "didn't find packaged hub interface for package " + packageString));
    logger.n("package: %s, fromDep: %s", packageElement, fromDep);
    for (BindingKey key : fromDep.keySet()) {
      result.replaceValues(key, fromDep.get(key));
    }
    return result;
  }

  private SetMultimap<BindingKey, DependencyInfo> collectInternalDependencyMap() {
    SetMultimap<BindingKey, DependencyInfo> result = HashMultimap.create();
    for (TypeElement e : modules) {
      for (DependencyInfo dependencyInfo : dependencyCollector.collectFromModule(e)) {
        result.put(dependencyInfo.getDependant(), dependencyInfo);
      }
    }
    for (TypeElement e : ctorInjectedClasses) {
      DeclaredType type = (DeclaredType) e.asType();
      for (DependencyInfo dependencyInfo :
          dependencyCollector.collectFromCtorInjectedClass(e, type)) {
        result.put(dependencyInfo.getDependant(), dependencyInfo);
      }
    }
    return result;
  }

  private void collectModulesAndInjected() {
    for (Element e : packageElement.getEnclosedElements()) {
      if (e instanceof TypeElement) {
        collectFromClassOrInterface(
            (TypeElement) e, modules, ctorInjectedClasses, injectedClasses, utils,
            processingEnv, logger);
      }
    }
  }

  public static void collectFromClassOrInterface(
      TypeElement element,
      Set<TypeElement> modules,
      Set<TypeElement> ctorInjectedClasses,
      Set<TypeElement> injectedClasses,
      Utils utils, ProcessingEnvironment processingEnv, Logger logger) {
    logger.n("element %s", element);
    if (utils.isModule(element)) {
      modules.add(element);
    } else if (utils.findInjectedCtor(element) != null) {
      ctorInjectedClasses.add(element);
    } else if (utils.hasInjectedFieldsOrMethodsRecursively(element, processingEnv)){
      injectedClasses.add(element);
    }
    for (Element e : element.getEnclosedElements()) {
      if (e instanceof TypeElement) {
        collectFromClassOrInterface(
            (TypeElement) e, modules, ctorInjectedClasses, injectedClasses, utils,
            processingEnv, logger);
      }
    }
  }

  @Override
  protected String getPackageString() {
    return packageString;
  }

  @Override
  protected String getInjectorSimpleName() {
    return TIGER_PAKCAGED_INJECTOR;
  }

  @Override
  protected Set<TypeName> getSuperInterfaces() {
    return new HashSet<>();
  }

  @Override
  protected Set<BindingKey> getAllCtorParameters() {
    HashSet<BindingKey> result =
        Sets.newHashSet(
            BindingKey.get(
                ClassName.get(packageString, PackagedHubInterfaceGenerator.HUB_INTERFACE)));
    for (TypeElement i : nonNullaryCtorModules) {
      result.add(BindingKey.get(i.asType()));
    }
    return result;
  }

  @Override
  protected Pair<Set<BindingKey>, Set<BindingKey>> getProduced() {
    /**
     * we need to manipulate dependencyMap before generating each provided key. So do it in {@link
     * #doSpecific()}
     */
    return Pair.of(new HashSet<>(), new HashSet<>());
  }

  @Override
  protected String getProvisionMethodName(BindingKey key) {
    String result = Utils.getProvisionMethodName(dependencies, key);
    DependencyInfo dependencyInfo =
        Preconditions.checkNotNull(
            Iterables.getFirst(utils.getDependencyInfo(dependencies, key), null),
            "find no binding for key " + key);
    if (dependencyInfo
        .getDependencySourceType()
        .equals(DependencySourceType.COMPONENT_DEPENDENCIES_METHOD)) {
      result += "_forLocal";
    }
    return result;
  }


  @Override
  protected void addNewStatementToMethodSpec(
      MethodSpec.Builder methodSpecBuilder, DependencyInfo dependencyInfo, String newVarName) {
    // logger.n(" dependencyInfo : " + dependencyInfo);
    addNewStatementToMethodSpecByModuleOrCtor(methodSpecBuilder, dependencyInfo, newVarName);
    if (dependencyInfo.isMultiBinding()) {
      generateProvisionMethodForMultiBindingContributor(dependencyInfo);
    }
  }

  private void generateProvisionMethodForMultiBindingContributor(DependencyInfo dependencyInfo) {
    generateProvisionMethodFromModuleBinding(
        dependencyInfo, "", utils.getProvisonMethodNameForMultiBindingContributor(dependencyInfo));
  }

  private String generateStringToCreateFromClass(DependencyInfo dependencyInfo) {
    BindingKey key = dependencyInfo.getDependant();
    StringBuilder builder = new StringBuilder("new ")
        .append(utils.getCanonicalName((ClassName) key.getTypeName()))
        .append("(");
    List<BindingKey> dependencyKeys =
        utils.getCtorDependencies(dependencies, key);
    for (BindingKey dependencyKey : dependencyKeys) {
      // messager.printMessage(Kind.NOTE, "generateProvisionMethodFromClass. dependencyKey: "
      // + dependencyKey);
      generateProvisionMethodAndAppendAsParameter(dependencyKey, builder);
    }

    if (builder.substring(builder.length() - 2).equals(", ")) {
      builder.delete(builder.length() - 2, builder.length());
    }
    builder.append(")");
    return builder.toString();
  }

  @Override
  protected void doSpecific() {
    generateMethodsForProduced();
  }

  private void generateMethodsForProduced() {
    logger.n(packageString);
    Set<BindingKey> provided = new HashSet<>();
    for (BindingKey key : internalDependencyMap.keySet()) {
      if (utils.isNotSpecializedGeneric(key)) {
        continue;
      }
      Set<DependencyInfo> old = dependencies.replaceValues(key, internalDependencyMap.get(key));
      logger.n("provision method for key: %s", key);
      generateProvisionMethodIfNeeded(key);
      dependencies.replaceValues(key, old);
    }
    Set<BindingKey> injected = new HashSet<>();
    for (TypeElement i : injectedClasses) {
      logger.n("injection method: %s", i);
      generateInjectionMethod(BindingKey.get(i));
    }
  }
}
