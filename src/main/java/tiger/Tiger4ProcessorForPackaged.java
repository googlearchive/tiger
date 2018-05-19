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

import com.google.auto.service.AutoService;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import dagger.Component;
import dagger.Module;
import dagger.Subcomponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.inject.Inject;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

/**
 * Collects Dagger Component interfaces and generate Dagger Component implementions by wraping about
 * tiger injectors.
 */
@AutoService(Processor.class)
public class Tiger4ProcessorForPackaged extends TailChaserProcesssor {
  private static final String TAG = "Tiger4ProcessorForPackaged";
  private static final String MODULE_ANNOTATION_ELEMENT_SUBCOMPONENTS = "subcomponents";
  protected SetMultimap<TypeElement, BindingKey> componentToKeyMap = HashMultimap.create();

  private enum State{
    INITIAL,
    HUB_INTERFACE_GENERATED,
    PACKGED_INJECTOR_GENERATED,
    SUBCOMPONENT_PARENT_INTERFACE_GENERAED,
    HUB_INJECTOR_GENERATED
  }

  private State state = State.INITIAL;

  private String coreInjectorPackage;

  protected Map<TypeElement, TypeElement> componentToParentMap = new HashMap<>();
  private ScopeAliasCondenser scopeAliasCondenser;
  private Map<CoreInjectorInfo, CoreInjectorInfo> coreInjectorTree;
  private CoreInjectorInfo rootCoreInjectorInfo;
  private ScopeSizer scopeSizer;
  private SetMultimap<CoreInjectorInfo, TypeElement> scopedModules = HashMultimap.create();
  private Set<TypeElement> unscopedModules = new HashSet<>();
  private SetMultimap<CoreInjectorInfo, TypeElement> scopedPassedModules = HashMultimap.create();
  private Set<TypeElement> unscopedPassedModules = new HashSet<>();
  private Map<TypeElement, TypeElement> componentToBuilderMap = new HashMap<>();

  protected Collection<DependencyInfo> dependencyInfos;
  protected SetMultimap<BindingKey, DependencyInfo> dependencies;

  private Map<TypeElement, CoreInjectorInfo> componentToCoreInjectorMap;

  protected List<String> allRecoverableErrors = new ArrayList<>();

  private SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToBothComponentBuilderMap =
      HashMultimap.create();
  protected SetMultimap<TypeElement, TypeElement> componentToComponentDependencyMap;
  private SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToComponentDependencyMap;
  protected SetMultimap<TypeElement, BindingKey> componentToBindsInstanceMap;
  private SetMultimap<CoreInjectorInfo, BindingKey> coreInjectorToBindsInstanceMap;
  protected Set<TypeElement> componentBuilders;
  private SetMultimap<TypeElement, DependencyInfo> componentToBindingsFromDependenciesMap;
  private String topLevelInjectorPrefix = "Tiger";
  private String topLevelInjectorSuffix = "Injector";

  // This need to survive processing rounds so that (sub)components failed for recoverable error can
  // be completed in later round(s).
  private Set<TypeElement> doneEitherComponents = new HashSet<>();
  protected Set<TypeElement> allModules = new HashSet<>();
  private Set<Element> allInjected = new HashSet<>();
  protected Set<TypeElement> allEitherComponents = new HashSet<>();
  private boolean hubInjectorsGenerated;
  private boolean firstRound;
  protected DependencyCollector dependencyCollector;

  /**
   * Returns whether collecting is finished.
   */
  private boolean collectModulesAndClasses(Set<? extends TypeElement> annotations) {

    Set<TypeElement> newModules = utils.getTypedElements(roundEnvironment, Module.class);
    allModules.addAll(newModules);
    Set<Element> newClasses = utils.getTypedElements(roundEnvironment, Inject.class);
    allInjected.addAll(newClasses);
    logger.n("newModules \n %s\n %s",  newModules, newClasses);

    // Still could be wrong if manipulated carefull to screw it. Wont worry about that.
    boolean result = !firstRound && newModules.isEmpty() && newClasses.isEmpty();
    if (firstRound) {
      firstRound = false;
    }

    return result;
  }

  /**
   * Returns whether collecting is finished.
   */
  private boolean collectEitherComponents(Set<? extends TypeElement> annotations) {
    Set<TypeElement> newEitherComponents =
        utils.getTypedElements(roundEnvironment, Component.class, Subcomponent.class);
    allEitherComponents.addAll(newEitherComponents);
    logger.n("newEitherComponents: %s", newEitherComponents);

    // Still could be wrong if manipulated carefull to screw it. Wont worry about that.
    boolean result = !firstRound && newEitherComponents.isEmpty();
    if (firstRound) {
      firstRound = false;
    }

    return result;
  }

  @Override
  protected boolean handle(Set<? extends TypeElement> annotations) {
    boolean modulesAndClassesCollected = collectModulesAndClasses(annotations);
    boolean eitherComponentsCollected = collectEitherComponents(annotations);
    logger.w(
        "state: %s, \nallModules: %s\nallInjected: %s\nallComponent: %s ",
        state, allModules, allInjected, allEitherComponents);
    switch (state) {
      case INITIAL:
        // state = State.PACKGED_INJECTOR_GENERATED;
        // TODO: remove useless states.
        // TODO: handle this properly.
        if (modulesAndClassesCollected) {
          generateHubInterface(allModules, allInjected, processingEnv, roundEnvironment, utils);
          state = State.HUB_INTERFACE_GENERATED;
        }
        return false;
      case HUB_INTERFACE_GENERATED:
        generatePackagedInjectors(allModules, allInjected, processingEnv, roundEnvironment, utils);
        state = State.PACKGED_INJECTOR_GENERATED;
        return false;
      case PACKGED_INJECTOR_GENERATED:
        if (eitherComponentsCollected) {
          addSiblingsInSamePackage(allEitherComponents);
          componentToParentMap = collectComponentToParentMap(allEitherComponents);
          generateSubcomponentParentInterfaces();
          state = State.SUBCOMPONENT_PARENT_INTERFACE_GENERAED;
        }
        return false;
      case SUBCOMPONENT_PARENT_INTERFACE_GENERAED:
        if (eitherComponentsCollected) {
          handleHub();
          state = State.HUB_INJECTOR_GENERATED;
        }
        return false;
      case HUB_INJECTOR_GENERATED:
        return true;
    }
    return false;
  }

  private void generateSubcomponentParentInterfaces() {
    SubcomponentParentInterfaceGenerator subcomponentParentInterfaceGenerator = new SubcomponentParentInterfaceGenerator(
        processingEnv, utils);
    for (TypeElement c : allEitherComponents) {
      if (utils.isComponent(c)) {
        continue;
      }
      subcomponentParentInterfaceGenerator.generate(c, componentToParentMap);
    }
  }

  private void addSiblingsInSamePackage(Set<TypeElement> allEitherComponents) {
    for (String p : utils.getPackages(allEitherComponents, Sets.newHashSet())) {
      PackageElement packageElement = elements.getPackageElement(p);
      for (Element e : packageElement.getEnclosedElements()) {
        if (utils.isEitherComponent(e)) {
          allEitherComponents.add((TypeElement) e);
        }
      }
    }
  }


  protected void handleHub() {
    logger.w("");
    /** Empty, to be overridden in {@link Tiger3ProcessorForComponent} */
  }

  private void generateHubInterface(
      Set<TypeElement> allModules,
      Set<Element> allInjected,
      ProcessingEnvironment processingEnv,
      RoundEnvironment roundEnvironment,
      Utils utils) {
    logger.n("started");
    PackagedHubInterfaceGenerator packagedHubInterfaceGenerator =
        new PackagedHubInterfaceGenerator(processingEnv, roundEnvironment, utils);
    forAllPackages(
        allModules,
        allInjected,
        processingEnv,
        roundEnvironment,
        utils,
        p -> {
          packagedHubInterfaceGenerator.generate(p);
        });
  }

  protected Set<TypeElement> getAllEitherComponentBuilders(Set<TypeElement> components) {
    Set<TypeElement> result = new HashSet<>();
    for (TypeElement c : components) {
      TypeElement builder = utils.getEitherComonentBuilder(c);
      if (builder != null) {
          result.add(builder);
      }
    }
    logger.n("result: " + result);
    return result;
  }

  /** Parent (sub)component is excluded. */
  protected SetMultimap<TypeElement, TypeElement> collectComponentToComponentDependencyMap(
      Set<TypeElement> components) {
    logger.n( "collectComponentToComponentDependenciesMap");
    SetMultimap<TypeElement, TypeElement> result = HashMultimap.create();
    for (TypeElement c : components) {
      if (utils.isSubcomponent(c)) {
        continue;
      }
      for (TypeElement dep : utils.getComponentDependencies(c)) {
        result.put(c, dep);
      }
    }

    logger.n("" + result);
    return result;
  }

  /*
   * TODO: this handle builder setter as well as @BindsInstance. Therefore includes every
   * dependencies. Duplicates are removed when in {@link #generateWrapperComponentBuilder}.
   */
  protected SetMultimap<TypeElement, BindingKey> collectComponentToBindsInstanceMap() {
    SetMultimap<TypeElement, BindingKey> result = HashMultimap.create();
    for (TypeElement i : allEitherComponents) {
      result.putAll(i, utils.getBindsInstances(i));
    }

    logger.n("result: " + result);
    return result;
  }

  // Return mapping from (sub)component to their parent (sub)component.
  // WARNING: we assume different TypeElement instance for the same entity are equal. Theoretically
  // it could be wrong. But so far it works fine. We can refactor to use TypeName.
  protected Map<TypeElement, TypeElement> collectComponentToParentMap(Set<TypeElement> components) {
    Map<TypeElement, TypeElement> result = new HashMap<>();
    Set<TypeElement> all = Sets.newHashSet(components);
    Set<TypeElement> found = Sets.newHashSet();
    Set<TypeElement> work = Sets.newHashSet(components);

    int count = 1;
    while (!work.isEmpty()) {
      // logger.n("work" + work);

      // if (count++ > 5) {
      //   logger.n("too many rounds");
      //   return result;
      // }
      result.putAll(collectComponentToParentMapByDependencies(work));
      result.putAll(collectComponentToParentMapByFactoryMethod(work));
      result.putAll(collectComponentToParentMapByModule(work));
      result.putAll(collectComponentToParentMapByContributesAndroidInjector(work));
      found.addAll(result.keySet());
      found.addAll(result.values());
      work = Sets.newHashSet(Sets.difference(found, all));
      // logger.n("found" + found);
      // logger.n("all" + all);
      // logger.n("work" + work.isEmpty() + work);
      all.addAll(found);
    }

    // trimResult(result);
    logger.n("result" + result);


    return result;
  }

  private Map<TypeElement, TypeElement> collectComponentToParentMapByContributesAndroidInjector(
      Set<TypeElement> components) {
    Map<TypeElement, TypeElement> result = new HashMap<>();

    for (TypeElement c : components) {
      for (TypeElement m : utils.findAllModulesOfComponentRecursively(c)) {
        Set<TypeElement> subcomponents = Preconditions.checkNotNull(
            utils.collectSubomponentsByContributesAndroidInjectorInModule(elements, messager, m),
            "cannot load subcomponent(s) generated by @ContributesAndroidInjector from module: "
                + m);
        for (TypeElement subcomponent : subcomponents) {
          result.put(subcomponent, c);
        }
      }
    }
    logger.n("result" + result);

    return result;
  }

  /** Return mappings by checking (sub)component -> module -> subcomponent. */
  private Map<TypeElement, TypeElement> collectComponentToParentMapByModule(
      Set<TypeElement> components) {
    Map<TypeElement, TypeElement> result = new HashMap<>();

    for (TypeElement c : components) {

      for (TypeElement module : utils.findAllModulesOfComponentRecursively(c)) {
        AnnotationMirror annotationMirror = utils.getAnnotationMirror(module, Module.class);
        AnnotationValue subcomponentsAnnotationValue = Utils
            .getAnnotationValue(elements, annotationMirror, MODULE_ANNOTATION_ELEMENT_SUBCOMPONENTS);
        if (subcomponentsAnnotationValue == null) {
          continue;
        }
        for (AnnotationValue av : (List<AnnotationValue>) subcomponentsAnnotationValue.getValue()) {
          TypeElement subcomponent = (TypeElement)((DeclaredType)av.getValue()).asElement();
          Preconditions.checkState(utils.isSubcomponent(subcomponent));
          result.put(subcomponent, c);
        }
      }
    }
    logger.n("result" + result);

    return result;
  }

  /**
   * Return mappings by checking Subcomponent factory method(provision method) in (sub)eitherComponents.
   */
  private Map<? extends TypeElement, ? extends TypeElement>
  collectComponentToParentMapByFactoryMethod(Set<TypeElement> components) {
    Map<TypeElement, TypeElement> result = new HashMap<>();

    for (TypeElement c : components) {
      utils.traverseAndDo(
          types,
          (DeclaredType) c.asType(),
          c,
          pair -> {
            Element e = pair.getSecond();
            // logger.n( "component: %s method: %s", c, e);
            if (utils.isSubcomponentProvisionMethod(e)
                || utils.isSubcomponentBuilderProvisionMethod(e)) {
              // logger.n( "1");
              TypeElement returnType = utils.getReturnTypeElement((ExecutableElement) e);
              if (utils.isSubcomponentBuilder(returnType)) {
                returnType = (TypeElement) returnType.getEnclosingElement();
              }

              // logger.n( "2");

              // found
              result.put(returnType, c);
            }
            return null;
          });
    }

    logger.n("result" + result);
    return result;
  }

  /**
   * Returns mappings by checking Component.dependencies.
   */
  private Map<TypeElement, TypeElement> collectComponentToParentMapByDependencies(
      Set<TypeElement> components) {
    logger.n("eitherComponents " + components);
    Map<TypeElement, TypeElement> result = new HashMap<>();

    for (TypeElement c : components) {
//      logger.n("" + c);
      if (!utils.isComponent(c)) {
        continue;
      }
      // logger.n( "2");

      TypeElement parent = getComponentParent(c);
      if (parent != null) {
        result.put(c, parent);
      }
    }
    logger.n("result: " + result);

    return result;
  }
  /** Returns parent (sub)component if one exist, null otherwise. */
  private @Nullable
  TypeElement getComponentParent(TypeElement c) {
    Preconditions.checkArgument(utils.isComponent(c));
    TypeElement parent = null;
    for (TypeElement dependency : utils.getComponentDependencies(c)) {
      if (utils.isEitherComponent(dependency)) {
        if (parent != null) {
          logger.e(String.format("Found multiple parent (sub)components for %s.", c));
        }
        parent = dependency;
      }
    }
    return parent;
  }

  /**
   * TODO: This might create multiple packaged injector that are not identical to each other, e.g.,
   * different build target in a package/dir can generated different modules/ctor-injected-class.
   * Add explicit per package build target that is depended BY others targes in the package.
   */
  private void generatePackagedInjectors(
      Set<TypeElement> allModules,
      Set<Element> allInjected,
      ProcessingEnvironment processingEnv,
      RoundEnvironment roundEnvironment,
      Utils utils) {
    forAllPackages(allModules, allInjected, processingEnv, roundEnvironment, utils, p -> {
      new PackagedInjectorGenerator(p, processingEnv, roundEnvironment, utils).generate();
    });
  }

  private void forAllPackages(
        Set<TypeElement> allModules,
        Set<Element> allInjected,
        ProcessingEnvironment processingEnv,
        RoundEnvironment roundEnvironment,
        Utils utils,
        Consumer<String> consumer) {
    Set<String> packages = utils.getPackages(allModules, allInjected);
    logger.n(" packages:" + packages);
    for (String p : packages) {
      consumer.accept(p);
    }
  }

  @Override
  protected Set<String> getAnnotationTypesToChase() {
    return Sets.newHashSet(
        Module.class.getCanonicalName(),
        Inject.class.getCanonicalName(),
        Component.class.getCanonicalName(),
        Subcomponent.class.getCanonicalName());
  }
}
