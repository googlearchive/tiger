package tiger;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Generated;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

/** Created by freemanliu on 5/7/18. */
public class SubcomponentParentInterfaceGenerator {

  private static final String GENERATOR_NAME = "dagger.SubcomponentParentInterfaceGenerator";
  private ProcessingEnvironment processingEnv;
  private Utils utils;
  private Logger logger;

  public SubcomponentParentInterfaceGenerator(ProcessingEnvironment processingEnv, Utils utils) {
    this.processingEnv = processingEnv;
    this.utils = utils;
    logger = new Logger(processingEnv.getMessager(), Kind.WARNING);
  }

  public void generate(
      TypeElement subcomponent, Map<TypeElement, TypeElement> componentToParentMap) {
    ExtraDependenciesOnParentCalculator extraDependenciesOnParentCalculator =
        ExtraDependenciesOnParentCalculator.getInstance(componentToParentMap, processingEnv, utils);
    TypeSpec.Builder interfaceBuilder =
        TypeSpec.interfaceBuilder(getInterfaceName(subcomponent, utils))
            .addAnnotation(
                AnnotationSpec.builder(Generated.class)
                    .addMember("value", "$S", GENERATOR_NAME)
                    .build())
            .addModifiers(Modifier.PUBLIC);
    Set<BindingKey> done = new HashSet<>();
    for (BindingKey i : extraDependenciesOnParentCalculator.calculate(subcomponent, null)) {
      utils.generateAbstractProvisonMethodIfNeeded(
          interfaceBuilder, utils.getProvisionMethodName(i), i, done);
    }
    JavaFile javaFile =
        JavaFile.builder(utils.getPackageString(subcomponent), interfaceBuilder.build()).build();
    logger.n(
        "SubcomponentParentInterface package: %s, toString: %s",
        javaFile.packageName, javaFile.toString());
    try {
      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static String getInterfaceName(TypeElement c, Utils utils) {
    return utils.getComponentImplementationSimpleNameFromInterface(c) + "_" + "ParentInterface";
  }
}
