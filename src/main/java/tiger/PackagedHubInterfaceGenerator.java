package tiger;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Generated;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * <p>This collect all the modules and injected classes therefore MUST be called after the other
 * processing rounds that could generate those classes. (probably not the same round). We collect so
 * that multiple building targets in the same package can generate full and identical result.
 *  </p>
 *  <p>
 *   Packaged injectors are straight. They just reflect what the package provides. All that missed
 *   go to hub.
 * </p>
 *
 * <p>
 *   Scope is handled in package injectors, hub can not handle non public types. Therefore let packaged
 *   injectors handle it consistently.
 * </p>
 * <p>
 *   Multi-binding, builtin and boxing are handled in hub.
 * </p>
 *
 */
public class PackagedHubInterfaceGenerator {
  private static final String TAG = "HubInterfaceForPackageGenerator";

  // Hub is the toplevel-injector/dagger-(sub)component generated.
  public static final String HUB_INTERFACE = "TigerPackagedHubInterface";
  private final Types types;
  private final Elements elements;
  private final Messager messager;
  private ProcessingEnvironment processingEnv;
  private RoundEnvironment roundEnvironment;
  private Utils utils;
  private Set<TypeElement> modules;
  private Set<TypeElement> ctorInjectedClasses;
  private Set<TypeElement> injectedClasses;
  private PackageElement packageElement;
  private Builder interfaceBuilder;
  private String packageString;
  private final Logger logger;
  private Set<TypeElement> classesWithInjectionMethodGenerated = new HashSet<>();
  private Set<BindingKey> provisionMethodGenerated = new HashSet<>();

  public PackagedHubInterfaceGenerator(
      ProcessingEnvironment processingEnv, RoundEnvironment roundEnvironment, Utils utils) {
    types = processingEnv.getTypeUtils();
    elements = processingEnv.getElementUtils();
    messager = processingEnv.getMessager();
    this.processingEnv = processingEnv;
    this.roundEnvironment = roundEnvironment;
    this.utils = utils;
    logger = new Logger(messager, Kind.WARNING);
  }

  public void generate(String p) {
    logger.n("package: %s", p);
    provisionMethodGenerated.clear();
    interfaceBuilder =
        TypeSpec.interfaceBuilder(HUB_INTERFACE)
            .addAnnotation(
                AnnotationSpec.builder(Generated.class)
                    .addMember("value", "$S", "dagger." + TAG)
                    .build())
            .addModifiers(Modifier.PUBLIC);
    packageElement = elements.getPackageElement(p);
    modules = new HashSet<>();
    ctorInjectedClasses = new HashSet<>();
    injectedClasses = new HashSet<>();
    packageString = p;
    collectModulesAndInjected();
    generateInterfaceForHub();

    JavaFile javaFile = JavaFile.builder(p, interfaceBuilder.build()).build();
    try {
      // logger.n(
      //     "package:%s\n%s",
      //     p,
      //     new StringBuilder().append(javaFile.toJavaFileObject().getCharContent(true)).toString());

      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      messager.printMessage(Kind.ERROR, "Failed to write " + javaFile + "\n" + e);
    }
  }

  private void generateInterfaceForHub() {
    Set<BindingKey> hubInterfaceKeys = collectRequiredKeys(modules, ctorInjectedClasses,
        injectedClasses, utils, logger
    );
    removeNonInterfaceOnes(hubInterfaceKeys, utils);
    // logger.n(".generateInterfaceForHub: after remove\n" + hubInterfaceKeys);
    for (BindingKey key : hubInterfaceKeys) {
      utils.generateAbstractProvisonMethodIfNeeded(
          interfaceBuilder,
          getProvisionMethodNameForPackagedHubInterface(key),
          key,
          provisionMethodGenerated);
    }
    // logger.n("ctorInjectedClasses: %s", ctorInjectedClasses);
    // logger.n("injectedClass: %s", injectedClasses);
    for (TypeElement cls : utils.uniteSets(ctorInjectedClasses, injectedClasses)) {
      // packaged injection's injection methods need ancestor injection method.
      TypeElement ancestor = utils.getClosestInjectedAncestor(cls);
      if (ancestor != null && utils.isSelfAndEnclosingPublic(ancestor)) {
        generateAbstractInjectionMethod(ancestor);
      }
    }
  }

  private void generateAbstractInjectionMethod(TypeElement cls) {
    if (!classesWithInjectionMethodGenerated.add(cls)) {
      return;
    }
    // TODO: this can only handle generic where type variables do not involve DI. A more general
    // solution
    // is needed.
    TypeName typeName = TypeName.get(cls.asType());
    if (typeName instanceof ParameterizedTypeName) {
      typeName = ((ParameterizedTypeName) typeName).rawType;
    }
    MethodSpec.Builder builder = MethodSpec.methodBuilder("inject")
        .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
        .addParameter(typeName, "v");
    interfaceBuilder.addMethod(builder.build());
  }

  public static String getProvisionMethodNameForPackagedHubInterface(BindingKey key) {
    return Utils.getProvisionMethodName(key);
  }

  public static void removeNonInterfaceOnes(Set<BindingKey> hubInterfaceKeys, Utils utils) {
    Set<BindingKey> toRemove = new HashSet<>();
    for (BindingKey k : hubInterfaceKeys) {
      TypeName typeName = k.getTypeName();
      // generic ones
      if (utils.isGenericNotSpecialized(k.getTypeName())) {
        toRemove.add(k);
        continue;
      }
      // local ones
      if (!utils.isPublicallyAccessible(k.getTypeName())) {
        toRemove.add(k);
      }
    }
    for (BindingKey k : toRemove) {
      hubInterfaceKeys.remove(k);
    }
  }

  /**
   * Only direct dependencies, no recursive.
   * @param modules
   * @param ctorInjectedClasses
   * @param injectedClasses
   * @param utils
   * @param logger
   */
  public static Set<BindingKey> collectRequiredKeys(
      Set<TypeElement> modules, Set<TypeElement> ctorInjectedClasses,
      Set<TypeElement> injectedClasses, Utils utils, Logger logger) {
    Set<BindingKey> result = new HashSet<>();
    for (TypeElement e : modules) {
      collectRequiredKeysFromModule(result, e, utils);
      // logger.n("after module %s\n%s", e, result);
    }
    for (TypeElement e : ctorInjectedClasses) {
      logger.n("ctor: %s", e);
      utils.collectRequiredKeysFromClass(result, e);
      // logger.n("after ctor %s\n%s", e, result);

    }
    for (TypeElement e : injectedClasses) {
      utils.collectRequiredKeysFromClass(result, e);
      // logger.n("after class %s\n%s", e, result);

    }
    return result;
  }

  public static void collectRequiredKeysFromModule(Set<BindingKey> result, TypeElement module,
      Utils utils) {
    for (Element e : module.getEnclosedElements()) {
      if (!utils.isProvisionMethodInModule(e)) {
        continue;
      }
      List<BindingKey> keys = utils
          .getDependenciesFromExecutableElement((ExecutableElement) e);
      // utils.checkKeys(keys, e);
      result.addAll(keys);
    }
    TypeElement superCls = utils.getSuper(module);
    if (superCls != null) {
      collectRequiredKeysFromModule(result, superCls, utils);
    }
  }

  private void collectModulesAndInjected() {
    for (Element e : packageElement.getEnclosedElements()) {
      if (e instanceof TypeElement) {
        PackagedInjectorGenerator.collectFromClassOrInterface(
            (TypeElement) e, modules, ctorInjectedClasses, injectedClasses, utils,
            processingEnv, logger);
      }
    }
  }
}
