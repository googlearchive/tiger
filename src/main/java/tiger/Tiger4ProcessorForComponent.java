package tiger;

import com.google.auto.service.AutoService;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Generated;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;

/**
 * Created by freemanliu on 4/27/18.
 */
@AutoService(Processor.class)

public class Tiger4ProcessorForComponent extends Tiger4ProcessorForPackaged {
  private static final String TAG = "Tiger4ProcessorForComponent";

  static public TypeSpec.Builder debugBuilder = TypeSpec.classBuilder("debug");

  @Override
  protected void handleHub() {
    prepairForHubInjectorGeneration();
    generateHubInjectors(allEitherComponents, componentToParentMap);
    JavaFile javaFile = JavaFile.builder("tiger", debugBuilder.build()).build();
    try {
      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      logger.e("write to %s failed: %s", javaFile, e);
    }
  }

  private void prepairForHubInjectorGeneration() {
    dependencyCollector = DependencyCollector.getInstance(processingEnv, utils);

//    addChildrenAndRemoveSub(allEitherComponents, componentToParentMap);

    // TODO; remove the hack and add subcomponent interface.
    // allEitherComponents.addAll(componentToParentMap.keySet());
    // allEitherComponents.addAll(componentToParentMap.values());

    componentBuilders = getAllEitherComponentBuilders(allEitherComponents);

    componentToBindsInstanceMap = collectComponentToBindsInstanceMap();

    componentToComponentDependencyMap = collectComponentToComponentDependencyMap(allEitherComponents);

    List<TypeElement> sortedEitherComponents =
        utils.sortByFullName(allEitherComponents);
    // logger.w("%s", sortedEitherComponents);
    // sortedEitherComponents = Lists.newArrayList(sortedEitherComponents);
    // logger.w("%s", sortedEitherComponents);
    // if (sortedEitherComponents.size() > 10) {
    //   int skip = 3;
    //   for (int i = 0; i < skip; i++) {
    //     sortedEitherComponents.remove(0);
    //   }
    // }
    // for (TypeElement c : sortedEitherComponents) {
    //   logger.w("component: %s", c);
    //
    //   componentToKeyMap.putAll(
    //       c,
    //       DependencyCollector.collectionToMultimap(
    //           dependencyCollector.collectForOne(
    //               c,
    //               componentToParentMap.get(c),
    //               componentToComponentDependencyMap.get(c),
    //               componentToBindsInstanceMap.get(c),
    //               allRecoverableErrors)
    //           ).keySet());
    // }
    // messager.printMessage(Kind.WARNING,"prepairForHubInjectorGeneration");
  }

  private void addChildrenAndRemoveSub(Set<TypeElement> allEitherComponents,
      Map<TypeElement, TypeElement> componentToParentMap) {
    SetMultimap eitherComponentToChildrenMap = HashMultimap.create();
    for (Map.Entry<TypeElement, TypeElement> i : componentToParentMap.entrySet()) {
      eitherComponentToChildrenMap.put(i.getValue(), i.getKey());
    }
    Set<TypeElement> toAdd = new HashSet<>();
    for (TypeElement i : allEitherComponents) {
      toAdd.addAll(eitherComponentToChildrenMap.get(i));
    }
    Set<TypeElement> toRemove = new HashSet<>();
    for (TypeElement i : allEitherComponents) {
      if (utils.isSubcomponent(i)) {
        toRemove.add(i);
      }
    }
    for (TypeElement i : toRemove) {
      allEitherComponents.remove(i);
    }
    for (TypeElement i : toAdd) {
      allEitherComponents.add(i);
    }
  }

  private void generateHubInjectors(Set<TypeElement> allEitherComponents,
      Map<TypeElement, TypeElement> componentToParentMap) {

    // logger.n("allEitherComponents: %s", allEitherComponents);
    // messager.printMessage(Kind.WARNING, "allEitherComponents: " + allEitherComponents);
    logger.w("componentToParentMap: %s", componentToParentMap);
    Set<TypeElement> filtered = new HashSet<>();
        // filtered = new HashSet<>(
        //     Sets.filter(
        //         allEitherComponents, i -> {
        //           String name = i.getSimpleName().toString();
        //           TypeElement parent = componentToParentMap.get(i);
        //           String parentName = parent == null? "" : parent.getSimpleName().toString();
        //           return name.contains("ApplicationComponent") ||
        //               name.contains("ServiceComponent") ||
        //               name.contains("ArWalkingComponent") ||
        //               name.contains("GmmChimeComponent") ||
        //               parentName.contains("ServiceComponent");
        //         }));
    // sort to make the output deterministic, does not affect function.
    // for (TypeElement c: utils.sortByFullName(allEitherComponents)) {
    // logger.n("filtered: %s", filtered);
    filtered = allEitherComponents;
    int count = 0;
    List<TypeElement> sorted = utils.sortByFullName(filtered);
    logger.w("sorted components: %s", sorted);
    for (TypeElement c : sorted) {
      if (c.getQualifiedName().toString().contains("Fragment") && count < 0) {
        count ++;
        continue;
      }
      long t = System.currentTimeMillis();
      processEitherComponent(roundEnvironment, c);
      // profilerMethod("generate" + c.getQualifiedName(), System.currentTimeMillis() - t);
    }
  }

  public static void profilerMethod(String methodName, long millis) {
    debugBuilder.addMethod(
        MethodSpec.methodBuilder(methodName.replace(".", "_") + "_" + System.nanoTime())
            .addAnnotation(
                AnnotationSpec.builder(Generated.class)
                    .addMember("value", "$S", "time used: " + millis + " millis")
                    .build())
            .build());
  }

  /**
   * Returns whether the given (sub)component finally has a Component ancestor or itself is a
   * component.
   */
  private boolean rootedInComponent(TypeElement eitherComponent) {
    while (eitherComponent != null && !utils.isComponent(eitherComponent)) {
      eitherComponent = componentToParentMap.get(eitherComponent);
    }
    return eitherComponent != null;
  }

  private void processEitherComponent(RoundEnvironment env, TypeElement eitherComponent) {
    long startTime = System.currentTimeMillis();

    logger.w("process (sub)component: " + eitherComponent);
    // if (!rootedInComponent(eitherComponent)) {
    //   logger.w("not component ancester, igore: %s", eitherComponent);
    //   return;
    // }

    // TODO: restore the this
    //verifyComponents(eitherComponents);

    logger.n("allModules:  %s,\n %s", allModules.size(), allModules);
    dependencyInfos =
        dependencyCollector.collectForOne(
            eitherComponent,
            componentToParentMap.get(eitherComponent),
            componentToComponentDependencyMap.get(eitherComponent),
            componentToBindsInstanceMap.get(eitherComponent), allRecoverableErrors);

    logger.n("componentToKeyMap: %s", componentToKeyMap);


    // componentToBindingsFromDependenciesMap = dependencyCollector.collectFromComponentDependencies(
    //     componentToComponentDependencyMap, componentToCoreInjectorMap);
    // logger.n("all modules: "
    //         + allModules
    //         + "\nscopedModules: \n"
    //         + scopedModules
    //         + "\nunscopedModules:\n"
    //         + unscopedModules
    //         + "\nscopedPassedModules:\n"
    //         + scopedPassedModules
    //         + "\nunscopedPassedModules: \n"
    //         + unscopedPassedModules
    // );
//    logger.n( String.format(
//        "TigerDaggerGeneratorProcessor.process(). all dependencyInfos: %s", dependencyInfos));

    Set<BindingKey> requiredKeys =
        dependencyCollector.getRequiredKeys(allEitherComponents, dependencyInfos);

    if (!allRecoverableErrors.isEmpty()) {
      logger.n( "allRecoverableErrors:");
      for (String error : allRecoverableErrors) {
        logger.e( error);
      }
    }

    addEitherComponentAndAncestersToKeyMap(eitherComponent);
    dependencies = DependencyCollector.collectionToMultimap(dependencyInfos);
    HubInjectorGenerator4 hubInjectorGenerator =
        new HubInjectorGenerator4(
            eitherComponent,
            dependencies,
            utils.findAllModulesOfComponentRecursively(eitherComponent),
            componentToParentMap,
            componentToKeyMap,
            componentToComponentDependencyMap.get(eitherComponent),
            componentToBindsInstanceMap.get(eitherComponent),
            processingEnv,
            utils);
    hubInjectorGenerator.generate();

    if (allRecoverableErrors.isEmpty()) {
    } else if (env.processingOver()) {
      for (String error : allRecoverableErrors) {
        logger.e( error);
      }
    }

    logger.n("time: %s ms", (System.currentTimeMillis() - startTime));
  }

  private void addEitherComponentAndAncestersToKeyMap(TypeElement eitherComponent) {
    while (eitherComponent != null) {
      addEitherComponentToKeyMap(eitherComponent);
      eitherComponent = componentToParentMap.get(eitherComponent);
    }
  }

  private void addEitherComponentToKeyMap(TypeElement eitherComponent) {
    if (componentToKeyMap.containsKey(eitherComponent)
        && !componentToKeyMap.get(eitherComponent).isEmpty()) {
      return;
    }
    componentToKeyMap.putAll(
        eitherComponent,
        DependencyCollector.collectionToMultimap(
                dependencyCollector.collectForOne(
                    eitherComponent,
                    componentToParentMap.get(eitherComponent),
                    componentToComponentDependencyMap.get(eitherComponent),
                    componentToBindsInstanceMap.get(eitherComponent),
                    allRecoverableErrors))
            .keySet());
  }

}
