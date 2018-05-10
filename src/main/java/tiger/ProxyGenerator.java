package tiger;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import com.squareup.javapoet.TypeVariableName;
import dagger.Module;
import java.io.IOException;
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

  public ProxyGenerator(ProcessingEnvironment env, Utils utils) {
    this.env = env;
    messager = env.getMessager();
    this.utils = utils;
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
      generateForPackage(p, packageToModuleMap.get(p), packageToClassWithInjectMap.get(p));
    }
    SetMultimap<PackageElement, TypeElement> packageElementTypeElementMap = toMap(modules);
  }

  private void generateForPackage(
      PackageElement p, Set<TypeElement> modules, Set<TypeElement> classesWithInject) {
    // messager.printMessage(
    //     Kind.NOTE,
    //     TAG
    //         + ".generateForPackage: "
    //         + p
    //         + "\nmodules: \n"
    //         + modules
    //         + " cls: "
    //         + classesWithInject);
    TypeSpec.Builder typeBuilder =
        TypeSpec.classBuilder(ClassName.get(p.getQualifiedName().toString(), TIGER_PROXY_NAME))
            .addAnnotation(AnnotationSpec.builder(Generated.class)
                .addMember("value", "$S", GENERATOR_NAME).build())
            .addModifiers(Modifier.PUBLIC);
    for (TypeElement m : modules) {
      addMethodsCallingModuleMethods(typeBuilder, m);
    }
    for (TypeElement c : classesWithInject) {
      addCallingMethodForCtorInjectedClass(typeBuilder, c);
      addMethodSettingFields(typeBuilder, c);
      addMethodsCallingClassMethods(typeBuilder, c);
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
              // + javaFile.toString()
              + "\n");
      javaFile.writeTo(env.getFiler());
    } catch (IOException e) {
      messager.printMessage(Kind.ERROR, e.toString());
    }
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

  // TODO: handle generic.
  private void addCallingMethodForCtorInjectedClass(Builder typeBuilder, TypeElement cls) {
    ExecutableElement injectedCtor = utils.findInjectedCtor(cls);
    if (injectedCtor == null) {
      return;
    }
    String generatedMethodName = utils.getGetMethodName(cls);
    addMethodCallingMethodOrCtor(
        typeBuilder, cls.asType(), cls, injectedCtor, generatedMethodName);
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
    TypeName moduleTypeName = isCtor ? null : TypeName.get(container.asType());
    StringBuilder builder = new StringBuilder();
    if (hasReturnValue) {
      builder.append("$T result = ");
    }
    if (isCtor) {
      builder.append("new $T(");
    } else if (isStatic) {
      builder.append("$T.").append(executableElement.getSimpleName()).append("(");
    } else {
      methodSpecBuilder.addParameter(moduleTypeName, "obj");
      builder.append("obj.").append(executableElement.getSimpleName()).append("(");
    }
    String varNameBase = "var";
    int varOrdinal = 1;
    List<? extends TypeMirror> arguments =
        ((ExecutableType) executableElement.asType()).getParameterTypes();
    for (TypeMirror parameter : arguments) {
      String varName = varNameBase + varOrdinal;
      methodSpecBuilder.addParameter(TypeName.get(parameter), varName);
      builder.append(varName).append(", ");
      varOrdinal++;
    }
    if (arguments.size() > 0) {
      builder.delete(builder.length() - 2, builder.length());
    }
    builder.append(")");
    TypeName clsType = TypeName.get(returnType);
    if (isCtor) {
      methodSpecBuilder.addStatement(builder.toString(), clsType, clsType);
    } else if (isStatic) {
      methodSpecBuilder.addStatement(builder.toString(), clsType, moduleTypeName);
    } else {
      methodSpecBuilder.addStatement(builder.toString(), clsType);
    }

    if (hasReturnValue) {
      methodSpecBuilder.addStatement("return result");
    }
    typeBuilder.addMethod(methodSpecBuilder.build());
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
