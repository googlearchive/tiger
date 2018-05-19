package tiger;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import com.squareup.javapoet.TypeVariableName;
import dagger.Module;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Generated;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * Generate proxy for each package. This wont be need had dagger does not support indirective module
 * inclusion. That's for later enhancement. The generated code can be bypassed if not necessary and
 * finally get removed by proguard. But now(2018/04/08), we need it. Also needed for library codes
 * that we cannot change.
 * TODO: handle error with {@link TigerDaggerGeneratorProcessor#allRecoverableErrors}
 *
 */
public class ProxyGenerator {
  private static final String TAG = "ProxyGenerator";
  private static final String GENERATOR_NAME = "dagger.TigerProxy";

  private static final String TIGER_PROXY_NAME = "TigerProxy";

  private final ProcessingEnvironment env;
  private final Messager messager;
  private final Utils utils;
  private final ClassName objectClassName = ClassName.get(Object.class);
  private final Logger logger;
  private final Types types;

  public ProxyGenerator(ProcessingEnvironment env, Utils utils) {
    this.env = env;
    messager = env.getMessager();
    types = env.getTypeUtils();
    this.utils = utils;
    logger = new Logger(messager, Kind.NOTE);
  }

  /**
   * This does not handle included modules.
   * clssesWithInject includes ctor injected, field injected and method injected classes.
   */
  public void generate(Set<TypeElement> modules, Set<TypeElement> classesWithInject) {
    SetMultimap<PackageElement, TypeElement> packageToModuleMap = toMap(modules);
    SetMultimap<PackageElement, TypeElement> packageToClassWithInjectMap =
        toMap(classesWithInject);

    Set<PackageElement> packages = Sets.newHashSet(packageToModuleMap.keySet());
    packages.addAll(packageToClassWithInjectMap.keySet());
    messager.printMessage(
        Kind.NOTE,
        TAG
            + ".generate: "
            + packages
            + "\nmodules:\n"
            + modules
            + "\ncls:\n"
            + classesWithInject);
    for (PackageElement p : packages) {
      generateForPackage(p);
    }
    SetMultimap<PackageElement, TypeElement> packageElementTypeElementMap = toMap(modules);
  }

  private void collectModulesAndInjected(
      PackageElement packageElement, Set<TypeElement> modules, Set<TypeElement> classesWithInject) {
    Set<TypeElement> ctorInjectedClasses = new HashSet<>();
    Set<TypeElement> injectedClasses = new HashSet<>();

    for (Element e : packageElement.getEnclosedElements()) {
      if (e instanceof TypeElement) {
        PackagedInjectorGenerator.collectFromClassOrInterface(
            (TypeElement) e, modules, ctorInjectedClasses, injectedClasses, utils, env, logger);
      }
    }
    classesWithInject.addAll(ctorInjectedClasses);
    classesWithInject.addAll(injectedClasses);
  }

  private void generateForPackage(PackageElement p) {
    Set<TypeElement> modules = new HashSet<>();
    Set<TypeElement> classesWithInject = new HashSet<>();
    // messager.printMessage(
    //     Kind.NOTE,
    //     TAG
    //         + ".generateForPackage: "
    //         + p
    //         + "\nmodules: \n"
    //         + modules
    //         + " cls: "
    //         + classesWithInject);

    collectModulesAndInjected(p, modules, classesWithInject);
    TypeSpec.Builder typeBuilder =
        TypeSpec.classBuilder(ClassName.get(p.getQualifiedName().toString(), TIGER_PROXY_NAME))
            .addAnnotation(AnnotationSpec.builder(Generated.class)
                .addMember("value", "$S", GENERATOR_NAME).build())
            .addModifiers(Modifier.PUBLIC);
    for (TypeElement m : modules) {
      addMethodsCallingModuleMethods(typeBuilder, m);
    }
    for (TypeElement c : classesWithInject) {
      // TODO: revisit this for generic handling.
      // if (utils.isGenericNotSpecialized(c.asType())) {
      //   continue;
      // }
      if (!utils.isGenericNotSpecialized(c.asType())) {
        addMethodCallingCtor(typeBuilder, c);
      }
      if (utils.hasInjectedFieldsOrMethodsRecursively(c, env)) {
        addInjectionMethod(typeBuilder, c);
      }
      // addMethodSettingFields(typeBuilder, c);
      // addMethodsCallingClassMethods(typeBuilder, c);
    }

    JavaFile javaFile =
        JavaFile.builder(p.getQualifiedName().toString(), typeBuilder.build()).build();
    try {
      messager.printMessage(
          Kind.NOTE,
          TAG
              + " write to file: "
              + javaFile.toJavaFileObject().getName()
              + "\n"
              + javaFile.toString()
              + "\n");
      javaFile.writeTo(env.getFiler());
    } catch (IOException e) {
      messager.printMessage(Kind.ERROR, e.toString());
    }
  }

  // Ancestor not injected.
  private void addInjectionMethod(Builder typeBuilder, TypeElement c) {
    String objName = "obj";
    TypeName targetType = utils.getClassName(ClassName.get(c.asType()));

    TypeName returnType = targetType;
    boolean targetTypeNeedsCast = !utils.isPublicallyAccessible(targetType);
    if (targetTypeNeedsCast) {
      returnType = objectClassName;
    }
    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(Utils.getInjectionMethodName(BindingKey.get(targetType)))
            .addModifiers(Modifier.PUBLIC)
            .addParameter(returnType, objName);

    for (VariableElement field : utils.getSortedInjectedFields(c, env)) {
      String fieldName = field.getSimpleName().toString();
      TypeName fieldTypeName = TypeName.get(field.asType());
      boolean needCast = !utils.isPublicallyAccessible(fieldTypeName);
      TypeName parameterTypeName = needCast ? objectClassName : fieldTypeName;
      methodSpecBuilder.addParameter(
          ParameterSpec.builder(parameterTypeName, fieldName)
//              .addAnnotation(AnnotationSpec.get(Utils.getQualifier(field)))
              .build());
      if (targetTypeNeedsCast) {
        methodSpecBuilder.addStatement(
            "(($T)$L).$L = ($T)$L", targetType, objName, fieldName, fieldTypeName, fieldName);
      } else {
        methodSpecBuilder.addStatement(
            "$L.$L = ($T)$L", objName, fieldName, fieldTypeName, fieldName);

      }
    }

    for (ExecutableElement method : utils.getSortedInjectedMethods(c, env)) {
      String methodName = method.getSimpleName().toString();
      StringBuilder builder =
          new StringBuilder();
      if (targetTypeNeedsCast) {
        builder.append("(($T) ");
      }
      builder.append(objName);
      if (targetTypeNeedsCast) {
        builder.append(")");
      }
      builder.append(".").append(methodName).append("(");
      List<TypeName> castedTypes = new ArrayList<>();
      if (targetTypeNeedsCast) {
        castedTypes.add(targetType);
      }
      for (VariableElement injectedMethodParameter : method.getParameters()) {
        String fieldName = injectedMethodParameter.getSimpleName().toString();
        String injectionMethodParameterName = methodName + "_" + fieldName;
        TypeName fieldTypeName = TypeName.get(injectedMethodParameter.asType());
        boolean needCast = !utils.isPublicallyAccessible(fieldTypeName);
        TypeName parameterTypeName = needCast ? fieldTypeName : objectClassName;
        methodSpecBuilder.addParameter(
            ParameterSpec.builder(fieldTypeName, injectionMethodParameterName)
                // .addAnnotation(AnnotationSpec.get(Utils.getQualifier(injectedMethodParameter)))
                .build());
        if (needCast) {
          builder.append("($T)");
          castedTypes.add(fieldTypeName);
        }
        builder.append(injectionMethodParameterName).append(", ");
      }
      if (builder.charAt(builder.length() - 1) != '(') {
        builder.delete(builder.length() - 2, builder.length());
      }
      builder.append(")");
      methodSpecBuilder.addStatement(builder.toString(), castedTypes.toArray());
    }
    typeBuilder.addMethod(methodSpecBuilder.build());
  }

  private void addMethodSettingFields(Builder typeBuilder, TypeElement c) {
    for (VariableElement field : utils.getInjectedFields(c, env)) {
      // messager.printMessage(Kind.NOTE, TAG + ".addMethodSettingFields: element " + e);
      MethodSpec.Builder methodSpecBuilder =
          MethodSpec.methodBuilder(utils.getMethodNameSettingField(c, field))
              .addModifiers(Modifier.PUBLIC)
              .addParameter(TypeName.get(c.asType()), "module")
              .addParameter(TypeName.get(field.asType()), "v")
              .addStatement("module.$L = v", field.getSimpleName());
      addTypeVariablesToMethod(c, methodSpecBuilder);
      typeBuilder.addMethod(methodSpecBuilder.build());
    }
  }

  private void addMethodsCallingClassMethods(Builder typeBuilder, TypeElement c) {
    for (ExecutableElement method : utils.getInjectedMethods(c, env)) {
      // messager.printMessage(Kind.NOTE, TAG + ".addMethodsCallingClassMethods: element " + e);
      addMethodCallingMethodOrCtor(
          typeBuilder,
          method.getReturnType(),
          c,
          method,
          utils.getMethodNameCallingMethod(c, method));
    }
  }

  private void addMethodCallingCtor(Builder typeBuilder, TypeElement cls) {
    // TODO: handle generic better here in a consistent way like generic injectors.
    // Here is just a hack to by pass generic for the case that generic does not involving in
    // injection.
    // Preconditions.checkArgument(!utils.isGenericNotSpecialized(cls.asType()), cls);
    ExecutableElement injectedCtor = utils.findInjectedCtor(cls);
    if (injectedCtor == null) {
      return;
    }
    String generatedMethodName = utils.getGetMethodName(cls);
    addMethodCallingMethodOrCtor(
        typeBuilder, types.erasure(cls.asType()), cls, injectedCtor, generatedMethodName);
  }

  /**
   * Handles ctor, class methods including module methods.
   */
  private void addMethodCallingMethodOrCtor(
      Builder typeBuilder,
      TypeMirror returnType,
      TypeElement container,
      ExecutableElement executableElement,
      String generatedMethodName) {

    boolean isCtor = executableElement.getKind().equals(ElementKind.CONSTRUCTOR);
    boolean isStatic = executableElement.getModifiers().contains(Modifier.STATIC);


    MethodSpec.Builder methodSpecBuilder =
        MethodSpec.methodBuilder(generatedMethodName)
            .addModifiers(Modifier.PUBLIC);
    boolean hasReturnValue = !returnType.getKind().equals(TypeKind.VOID);
    if (hasReturnValue) {
      methodSpecBuilder.returns(ClassName.get(returnType));
    }

    if (isCtor) {
      addTypeVariablesToMethod(container, methodSpecBuilder);
    }
    TypeName moduleTypeName = isCtor ? null : ClassName.get(container.asType());
    List<TypeName> types = new ArrayList<>();
    TypeName returnTypeName = ClassName.get(returnType);
    types.add(returnTypeName); if (isCtor) {
      types.add(returnTypeName);
    } else if (isStatic) {
      types.add(moduleTypeName);
    }
    StringBuilder builder = new StringBuilder();
    if (hasReturnValue) {
      builder.append("$T result = ");
    }
    if (isCtor) {
      builder.append("new $T(");
    } else if (isStatic) {
      builder.append("$T.").append(executableElement.getSimpleName()).append("(");
    } else {
      methodSpecBuilder.addParameter(moduleTypeName, "module");
      builder.append("module.").append(executableElement.getSimpleName()).append("(");
    }
    String varNameBase = "var";
    int varOrdinal = 1;
    List<? extends TypeMirror> arguments =
        ((ExecutableType) executableElement.asType()).getParameterTypes();
    for (TypeMirror parameter : arguments) {
      String varName = varNameBase + varOrdinal;
      TypeName typeName = TypeName.get(parameter);
      methodSpecBuilder.addParameter(toAccessibleType(typeName), varName);
      if (!utils.isPublicallyAccessible(typeName)) {
        types.add(typeName);
        builder.append("($T) ");
      }
      builder.append(varName).append(", ");
      varOrdinal++;
    }
    if (arguments.size() > 0) {
      builder.delete(builder.length() - 2, builder.length());
    }
    builder.append(")");
    methodSpecBuilder.addStatement(builder.toString(), types.toArray());

    if (hasReturnValue) {
      methodSpecBuilder.addStatement("return result");
    }
    typeBuilder.addMethod(methodSpecBuilder.build());
  }

  private TypeName toAccessibleType(TypeName typeName) {
    if (utils.isPublicallyAccessible(typeName)) {
      return typeName;
    } else {
      return objectClassName;
    }
  }

  private void addTypeVariablesToMethod(
      TypeElement container, MethodSpec.Builder methodSpecBuilder) {
    List<? extends TypeParameterElement> typeParameters = container.getTypeParameters();
    for (TypeParameterElement e : typeParameters) {
      methodSpecBuilder.addTypeVariable(TypeVariableName.get(e));
    }
  }

  private void addMethodsCallingModuleMethods(Builder typeBuilder, TypeElement module) {
    Preconditions.checkArgument(
        utils.hasAnnotationMirror(module, Module.class), "Expect a module but got " + module);
    for (Element e : module.getEnclosedElements()) {
      if (!utils.isMethod(e)) {
        continue;
      }
      ExecutableElement method = (ExecutableElement) e;

      if (utils.isBindsMethod(method)) {
        continue;
      }

      if (utils.isMultibindsMethod(method)) {
        continue;
      }
      if (!utils.isProvidesMethod(method)) {
        continue;
      }

      addMethodCallingMethodOrCtor(
          typeBuilder,
          method.getReturnType(),
          module,
          method,
          utils.getMethodNameCallingMethod(module, method));
    }
  }

  private SetMultimap<PackageElement, TypeElement> toMap(Set<TypeElement> typeElements) {
    SetMultimap<PackageElement, TypeElement> result = HashMultimap.create();
    for (TypeElement e : typeElements) {
      PackageElement packageElement = utils.getPackage(e);
      result.put(packageElement, e);
    }
    return result;
  }
}
