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

import com.google.android.apps.docs.tools.dagger.componentfactory.MembersInjector;
import com.google.auto.service.AutoService;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.Component;
import dagger.Module;
import dagger.Subcomponent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * Collects Dagger Component interfaces and generate Dagger Component implementions by wraping about
 * tiger injectors.
 */
@AutoService(Processor.class)
public class TigerDaggerGeneratorProcessor extends AbstractProcessor {
  private static final String TAG = "TigerDaggerGeneratorProcessor";
  private static final String COMPONANT_ANNOTATION_ELEMENT_DEPENDENCIES = "dependencies";
  private static final String MODULE_ANNOTATION_ELEMENT_SUBCOMPONENTS = "subcomponents";

  private String coreInjectorPackage;
  private Elements elements;
  private Types types;
  private Messager messager;

  private Map<TypeElement, TypeElement> componentToParentMap = new HashMap<>();
  private ScopeAliasCondenser scopeAliasCondenser;
  private Map<CoreInjectorInfo, CoreInjectorInfo> coreInjectorTree;
  private CoreInjectorInfo rootCoreInjectorInfo;
  private ScopeSizer scopeSizer;
  private SetMultimap<CoreInjectorInfo, TypeElement> scopedModules = HashMultimap.create();
  private Set<TypeElement> unscopedModules = new HashSet<>();
  private SetMultimap<CoreInjectorInfo, TypeElement> scopedPassedModules = HashMultimap.create();
  private Set<TypeElement> unscopedPassedModules = new HashSet<>();
  private Map<TypeElement, TypeElement> componentToBuilderMap = new HashMap<>();

  private Collection<DependencyInfo> dependencyInfos;
  private SetMultimap<BindingKey, DependencyInfo> dependencies;

  private Map<TypeElement, CoreInjectorInfo> componentToCoreInjectorMap;

  private List<String> allRecoverableErrors = new ArrayList<>();

  private boolean done;
  private CoreInjectorGenerator coreInjectorGenerator;
  private RoundEnvironment roundEnvironment;
  private SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToBothComponentBuilderMap =
      HashMultimap.create();
  private SetMultimap<TypeElement, TypeElement> componentToComponentDependencyMap;
  private SetMultimap<CoreInjectorInfo, TypeElement> coreInjectorToComponentDependencyMap;
  private SetMultimap<TypeElement, BindingKey> componentToBindsInstanceMap;
  private SetMultimap<CoreInjectorInfo, BindingKey> coreInjectorToBindsInstanceMap;
  private Set<TypeElement> components = new HashSet<>();
  private Set<TypeElement> componentBuilders;
  private SetMultimap<TypeElement, DependencyInfo> componentToBindingsFromDependenciesMap;
  private String topLevelInjectorPrefix = "Tiger";
  private String topLevelInjectorSuffix = "Injector";
  private Utils utils;

  @Override
  public synchronized void init(ProcessingEnvironment env) {
    super.init(env);

    elements = env.getElementUtils();
    types = env.getTypeUtils();
    messager = env.getMessager();
  }

  /**
   * Because this processor needs output of the @ContributesAndroidInjector, if it cannot find
   * the subcomponents generated by that processor, it will just skip. But before that it needs
   * to collect all the (sub)components and their builders. {@link RoundEnvironment#getElementsAnnotatedWith(TypeElement)}
   * can only get the element in its round.
   *
   * TODO: currently, the will be executed for each library that contributes into the
   * app. That fine because all the Subcompoments is depended by their parent (sub)component
   * therefore we will get the full graph when building the lib with the parent component. In case
   * of component, the process is reversed. They depends on their parent if any. There could be
   * multiple libs with child components that depend on a parent component in another lib. In such
   * case, no build target will have a full graph. And the parent component will be generated
   * many times. But that's fine because all of them are identical. Doesn't matter which one is
   * instantiated. And java can have multiple definition of same class in its class path.
   */
  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
    messager.printMessage(Kind.NOTE, String.format("%s: process() ", TAG));
    roundEnvironment = env;
    utils = new Utils(processingEnv, roundEnvironment);
    long startTime = System.currentTimeMillis();

    if (done) {
      return false;
    }

    Set<TypeElement> newlyFound = utils.getTypedElements(env, Component.class/*, Subcomponent.class*/);
    messager.printMessage(Kind.NOTE, TAG + ".process: newly found components " + newlyFound);

    components.addAll(newlyFound);
    messager.printMessage(Kind.NOTE, TAG + ".process: components " + components);
    completeComponents(components);
    messager.printMessage(Kind.NOTE, TAG + ".process: completed components " + components);

    //componentBuilders.addAll(getTypeElements(env, Component.Builder.class, Subcomponent.Builder.class));
    /**
     * TODO: handle it with {@link #allRecoverableErrors}.
     */
    if (needToWaitContributesAndroidInjector()) {
      messager.printMessage(Kind.NOTE, "waiting for ContributesAndroidInjector to finish.");
      return false;
    }

    componentToParentMap = collectComponentToParentMap(components);

    /**
     * This includes the (sub)componens from libs that cannot be got by {@link
     * RoundEnvironment#getElementsAnnotatedWith(TypeElement)}
     */
    components.addAll(Sets.newHashSet(componentToParentMap.keySet()));
    components.addAll(componentToParentMap.values());
    // trimResult(components);

    messager.printMessage(Kind.NOTE, TAG + ".process: trimmed components " + components);

    componentBuilders = getAllEitherComponentBuilders(components);

    scopeAliasCondenser  = new ScopeAliasCondenser(messager, components);

    componentToCoreInjectorMap = collectComponentToCoreInjectorMap(components);
    componentToBindsInstanceMap = collectComponentToBindsInstanceMap();
    coreInjectorToBindsInstanceMap = collectCoreInjectorToBindsInstanceMap();

    componentToComponentDependencyMap = collectComponentToComponentDependencyMap(components);
    coreInjectorToComponentDependencyMap =
        collectCoreInjectorToComponentDependencyMap(components, componentToCoreInjectorMap);

    // TODO: restore the this
    //verifyComponents(components);


    coreInjectorPackage =
        getCoreInjectorPackage(Preconditions.checkNotNull(Iterables.getFirst(components, null)));

    coreInjectorTree = getCoreInjectorTree(components);
    messager.printMessage(Kind.NOTE, String.format("%s coreInjectorTree: %s", TAG, coreInjectorTree));

    if (coreInjectorTree.isEmpty()) {
      rootCoreInjectorInfo = new CoreInjectorInfo(
          getScopeForComponent(Iterables.getOnlyElement(components), messager));
    } else {
      rootCoreInjectorInfo =Iterables.getOnlyElement(
          Sets.difference(Sets.newHashSet(coreInjectorTree.values()), coreInjectorTree.keySet()));
    }
//    messager.printMessage(Kind.NOTE, String.format("%s scopeSizer: %s", TAG, scopeSizer));
    scopeSizer = new TreeScopeSizer(coreInjectorTree, rootCoreInjectorInfo, messager);
//    messager.printMessage(Kind.NOTE, String.format("scopeSizer: %s", scopeSizer));
    scopedModules = HashMultimap.create();
    unscopedModules = new HashSet<>();
    getModulesInComponents(components, scopedModules, unscopedModules);
    for (CoreInjectorInfo coreInjectorInfo : scopedModules.keySet()) {
      scopedPassedModules.putAll(coreInjectorInfo,
          utils.getNonNullaryCtorOnes(scopedModules.get(coreInjectorInfo)));
    }
    unscopedPassedModules.addAll(utils.getNonNullaryCtorOnes(unscopedModules));

    Set<TypeElement> allModules = Sets.newHashSet(scopedModules.values());
    allModules.addAll(unscopedModules);
    messager.printMessage(
        Kind.NOTE,
        TAG + " .process: scopeModules: " + scopedModules + " unscopedModules: " + unscopedModules);
    DependencyCollector dependencyCollector = DependencyCollector.getInstance(processingEnv, utils);
    dependencyInfos =
        dependencyCollector.collect(
            allModules,
            components,
            componentToCoreInjectorMap,
            coreInjectorToComponentDependencyMap,
            componentToBindsInstanceMap,
            allRecoverableErrors);

    componentToBindingsFromDependenciesMap = dependencyCollector.collectFromComponentDependencies(
        componentToComponentDependencyMap, componentToCoreInjectorMap);
    // messager.printMessage(
    //     Kind.NOTE,
    //     "TigerDaggerGeneratorProcessor.process(). all modules: "
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
//    messager.printMessage(Kind.NOTE, String.format(
//        "TigerDaggerGeneratorProcessor.process(). all dependencyInfos: %s", dependencyInfos));

    Set<BindingKey> requiredKeys =
        dependencyCollector.getRequiredKeys(components, dependencyInfos);

    coreInjectorToBothComponentBuilderMap = collectCoreInjectorToBothComponentBuilderMap(env);
    ScopeCalculator scopeCalculator =
        new ScopeCalculator(
            scopeSizer,
            dependencyInfos,
            requiredKeys,
            componentToCoreInjectorMap,
            utils.reverseSetMultimapToMap(coreInjectorToBothComponentBuilderMap),
            scopeAliasCondenser,
            processingEnv, utils);
    allRecoverableErrors.addAll(scopeCalculator.initialize());

    if (!allRecoverableErrors.isEmpty()) {
      messager.printMessage(Kind.NOTE, "allRecoverableErrors:");
      for (String error : allRecoverableErrors) {
        messager.printMessage(Kind.ERROR, error);
      }
    }

    dependencies = DependencyCollector.collectionToMultimap(dependencyInfos);
    topLevelInjectorPrefix = "Dagger";
    topLevelInjectorSuffix = "Injector";
    coreInjectorGenerator =
        new CoreInjectorGenerator(
            dependencies,
            scopeCalculator,
            scopeAliasCondenser,
            scopedModules,
            unscopedModules,
            getCoreInjectorToComponentMap(components),
            coreInjectorToBothComponentBuilderMap,
            coreInjectorTree,
            rootCoreInjectorInfo,
            coreInjectorToComponentDependencyMap,
            coreInjectorToBindsInstanceMap,
            coreInjectorPackage,
            topLevelInjectorPrefix,
            topLevelInjectorSuffix,
            processingEnv,
            utils);
    coreInjectorGenerator.generate();

    generateWrapperComponents(components);

    if (allRecoverableErrors.isEmpty()) {
      done = true;
    } else if (env.processingOver()) {
      for (String error : allRecoverableErrors) {
        messager.printMessage(Kind.ERROR, error);
      }
    }

    messager.printMessage(
        Kind.NOTE, "process time: " + (System.currentTimeMillis() - startTime) + "ms");

    new ProxyGenerator(processingEnv, utils).generate(allModules, getAllCtorInjectedClasses(dependencyInfos));

    return false;
  }

  private Set<TypeElement> getAllCtorInjectedClasses(Collection<DependencyInfo> dependencyInfos) {
    Set<TypeElement> result = new HashSet<>();
    for (DependencyInfo d: dependencyInfos) {
      if (d.getDependencySourceType().equals(DependencySourceType.CTOR_INJECTED_CLASS)) {
        result.add(d.getSourceClassElement());
      }
    }
    return result;
  }

  /**
   * Find all the (sub)components in question. {@link Component}s are from annotation already. But
   * some {@link Subcomponent}s can only be traced by provision methods for them or their builders.
   */
  private void completeComponents(Set<TypeElement> components) {
    Set<TypeElement> work = Sets.newHashSet(components);
    Set<TypeElement> done = Sets.newHashSet();
    while (!work.isEmpty()) {
      TypeElement c = Preconditions.checkNotNull(Iterables.getFirst(work, null));
      work.remove(c);
      done.add(c);
      utils.traverseAndDo(
          types,
          (DeclaredType) c.asType(),
          c,
          pair -> {
            TypeMirror type = pair.getFirst();
            Element element = pair.getSecond();
            if (utils.isEitherComponentProvisionMethod(element)
                || utils.isEitherComponentBuilderProvisionMethod(element)) {
              TypeElement newFound =
                  (TypeElement)
                      ((DeclaredType) ((ExecutableType) type).getReturnType()).asElement();
              if (utils.isEitherComponentBuilder(newFound)) {
                newFound = (TypeElement) newFound.getEnclosingElement();
              }
              if (!components.contains(newFound)) {
                components.add(newFound);
                messager.printMessage(
                    Kind.NOTE,
                    TAG
                        + ".completeComponents found new "
                        + newFound
                        + "component: "
                        + c
                        + " method: "
                        + element);
              }
              if (!done.contains(newFound)) {
                work.add(newFound);
              }
            }
            return null;
          });
    }
  }

  private Set<TypeElement> getAllEitherComponentBuilders(Set<TypeElement> components) {
    Set<TypeElement> result = new HashSet<>();
    for (TypeElement c : components) {
      TypeElement builder = utils.findBuilder(elements, c);
      if (builder != null) {
        result.add(builder);
      }
    }
    messager.printMessage(Kind.NOTE, ".getAllEitherComponentBuilders: result: " + result);
    return result;
  }

  // private Set<TypeElement> getAllEitherComponents(Set<TypeElement> components) {
  //   Set<TypeElement> result = Sets.newHashSet(components);
  //   Set<TypeElement> working = Sets.newHashSet(components);
  //   while (!working.isEmpty()) {
  //     TypeElement c = Iterables.getFirst(working);
  //     f
  //   }
  //   return result;
  // }

  private boolean needToWaitContributesAndroidInjector() {
    // return !roundEnvironment.getElementsAnnotatedWith(ContributesAndroidInjector.class).isEmpty();
    for (TypeElement c : components) {
      for (Element m : getAllModulesOfComponentRecursively(c)) {
        if (utils.collectSubomponentsByContributesAndroidInjectorInModule(
                elements, messager, (TypeElement) m)
            == null) {
          return true;
        }
      }
    }

    return false;
  }

  private SetMultimap<CoreInjectorInfo, BindingKey> collectCoreInjectorToBindsInstanceMap() {
    SetMultimap<CoreInjectorInfo, BindingKey> result = HashMultimap.create();
    for (TypeElement c : componentToBindsInstanceMap.keySet()) {
      result.putAll(componentToCoreInjectorMap.get(c), componentToBindsInstanceMap.get(c));
    }
    messager.printMessage(Kind.NOTE, "collectCoreInjectorToBindsInstanceMap"
        + " result: " + result);
    return result;
  }

  /**
   * TODO: this handle builder setter as well as @BindsInstance. Therefore includes every
   * dependencies. Duplicates are removed when in {@link #generateWrapperComponentBuilder}.
   */
  private SetMultimap<TypeElement, BindingKey> collectComponentToBindsInstanceMap() {
    SetMultimap<TypeElement, BindingKey> result = HashMultimap.create();
    for (TypeElement b : componentBuilders) {
      Element enclosingElement = b.getEnclosingElement();
      for (Element e : elements.getAllMembers(b)) {
        if (utils.isBindsInstanceMethod(e)) {
          result.put(
              (TypeElement) enclosingElement,
              utils.getKeyForOnlyParameterOfMethod(types, (DeclaredType) (b.asType()), e));
        }
      }
    }

    messager.printMessage(Kind.NOTE, "collectComponentToBindsInstanceMap result: " + result);
    return result;
  }

  /**
   * Returns all dependencies of the give components, empty set if none. Parent (sub)component is
   * excluded.
   */
  private Collection<TypeElement> collectAllComponentDependencies(Set<TypeElement> components) {
    return collectCoreInjectorToComponentDependencyMap(components, componentToCoreInjectorMap)
        .values();
  }

  /** Parent (sub)component is excluded. */
  private SetMultimap<CoreInjectorInfo, TypeElement> collectCoreInjectorToComponentDependencyMap(
      Set<TypeElement> components, Map<TypeElement, CoreInjectorInfo> componentToCoreInjectorMap) {
    SetMultimap<CoreInjectorInfo, TypeElement> result = HashMultimap.create();

    for (TypeElement c : collectComponentToComponentDependencyMap(components).keySet()) {
      for (TypeElement dep : getComponentDependencies(c)) {
        if (!utils.isEitherComponent(dep)) {
          result.put(componentToCoreInjectorMap.get(c), dep);
        }
      }
    }
    return result;
  }

  /** Parent (sub)component is excluded. */
  private SetMultimap<TypeElement, TypeElement> collectComponentToComponentDependencyMap(
      Set<TypeElement> components) {
    messager.printMessage(Kind.NOTE, "collectComponentToComponentDependenciesMap");
    SetMultimap<TypeElement, TypeElement> result = HashMultimap.create();
    for (TypeElement c : components) {
      if (!utils.isComponent(c)) {
        continue;
      }
      for (TypeElement dep : getComponentDependencies(c)) {
        if (!utils.isEitherComponent(dep)) {
          result.put(c, dep);
        }
      }
    }

    messager.printMessage(Kind.NOTE, "result: " + result);
    return result;
  }

  /** Returns mapping from core injectors to the (sub_component_ builders it provides. */
  private SetMultimap<CoreInjectorInfo, TypeElement> collectCoreInjectorToBothComponentBuilderMap(
      RoundEnvironment env) {
    SetMultimap<CoreInjectorInfo, TypeElement> result = HashMultimap.create();

    for (TypeElement b : componentBuilders) {
      Element enclosingElement = b.getEnclosingElement();
      if (utils.isComponent(enclosingElement)) {
        result.put(rootCoreInjectorInfo, b);
      } else {
        Preconditions.checkState(utils.isSubcomponent(enclosingElement));
        result.put(coreInjectorTree.get(componentToCoreInjectorMap.get(enclosingElement)), b);
      }
    }

    messager.printMessage(
        Kind.NOTE, TAG + ".collectCoreInjectorToBothComponentBuilderMap result: " + result);
    return result;
  }

  // Return mapping from (sub)component to their parent (sub)component.
  // WARNING: we assume different TypeElement instance for the same entity are equal. Theoretically
  // it could be wrong. But so far it works fine. We can refactor to use TypeName.
  private Map<TypeElement, TypeElement> collectComponentToParentMap(Set<TypeElement> components) {
    Map<TypeElement, TypeElement> result = new HashMap<>();
    Set<TypeElement> all = Sets.newHashSet(components);
    Set<TypeElement> found = Sets.newHashSet();
    Set<TypeElement> work = Sets.newHashSet(components);

    int count = 1;
    while (!work.isEmpty()) {
      // messager.printMessage(
      //     Kind.NOTE, TAG + ".collectComponentToParentMap: work" + work);

      // if (count++ > 5) {
      //   messager.printMessage(
      //       Kind.NOTE, TAG + ".collectComponentToParentMap: too many rounds");
      //   return result;
      // }
      result.putAll(collectComponentToParentMapByDependencies(work));
      result.putAll(collectComponentToParentMapByFactoryMethod(work));
      result.putAll(collectComponentToParentMapByModule(work));
      result.putAll(collectComponentToParentMapByContributesAndroidInjector(work));
      found.addAll(result.keySet());
      found.addAll(result.values());
      work = Sets.newHashSet(Sets.difference(found, all));
      // messager.printMessage(
      //     Kind.NOTE, TAG + ".collectComponentToParentMap: found" + found);
      // messager.printMessage(
      //     Kind.NOTE, TAG + ".collectComponentToParentMap: all" + all);
      // messager.printMessage(
      //     Kind.NOTE, TAG + ".collectComponentToParentMap: work" + work.isEmpty() + work);
      all.addAll(found);
    }

    // trimResult(result);
    messager.printMessage(
        Kind.NOTE, TAG + ".collectComponentToParentMap: result" + result);


    return result;
  }

  private void trimResult(Map<TypeElement, TypeElement> result) {
    Set<TypeElement> toTrim = new HashSet<>();
    for (TypeElement i : result.keySet()) {
      String simpleName = i.getSimpleName().toString();
//      messager.printMessage(Kind.NOTE, TAG + ".trimResult: simpleName " + simpleName);
      if (shouldTrim(i) || shouldTrim(result.get(i))) {
        toTrim.add(i);
      }
    }
    for (TypeElement i : toTrim) {
      result.remove(i);
    }
  }

  private void trimResult(Set<TypeElement> result) {
    Set<TypeElement> toTrim = new HashSet<>();
    for (TypeElement i : result) {
      String simpleName = i.getSimpleName().toString();
//      messager.printMessage(Kind.NOTE, TAG + ".trimResult: simpleName " + simpleName);
      if (shouldTrim(i)) {
        toTrim.add(i);
      }
    }
    for (TypeElement i : toTrim) {
      result.remove(i);
    }
  }

  private boolean shouldTrim(TypeElement typeElement) {
    String simpleName = typeElement.getSimpleName().toString();
    HashSet<String> keep =
        utils.nameShouldBeKept;
    for (String s : keep) {
      if (simpleName.contains(s)) {
        return false;
      }
    }
    for (String s : utils.nameToTrim) {
      if (simpleName.contains(s)) {
        return true;
      }
    }
    return false;
  }

  private Map<TypeElement, TypeElement> collectComponentToParentMapByContributesAndroidInjector(
      Set<TypeElement> components) {
    Map<TypeElement, TypeElement> result = new HashMap<>();

    for (TypeElement c : components) {
      for (TypeElement m : getAllModulesOfComponentRecursively(c)) {
        Set<TypeElement> subcomponents = Preconditions.checkNotNull(
            utils.collectSubomponentsByContributesAndroidInjectorInModule(elements, messager, m),
            "cannot load subcomponent(s) generated by @ContributesAndroidInjector from module: "
                + m);
        for (TypeElement subcomponent : subcomponents) {
          result.put(subcomponent, c);
        }
      }
    }
    messager.printMessage(
        Kind.NOTE, TAG + ".collectComponentToParentMapByContributesAndroidInjector: result" + result);

    return result;
  }

  /** Return mappings by checking (sub)component -> module -> subcomponent. */
  private Map<TypeElement, TypeElement> collectComponentToParentMapByModule(
      Set<TypeElement> components) {
    Map<TypeElement, TypeElement> result = new HashMap<>();

    for (TypeElement c : components) {

      for (TypeElement module : getAllModulesOfComponentRecursively(c)) {
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
    messager.printMessage(
        Kind.NOTE, TAG + ".collectComponentToParentMapByModule: result" + result);

    return result;
  }

  /**
   * Returns mappings by checking Component.dependencies.
   */
  private Map<TypeElement, TypeElement> collectComponentToParentMapByDependencies(
      Set<TypeElement> components) {
    messager.printMessage(Kind.NOTE, "collectComponentToParentMapByDependencies"
    + " components " + components);
    Map<TypeElement, TypeElement> result = new HashMap<>();

    for (TypeElement c : components) {
//      messager.printMessage(Kind.NOTE, "1 " + c);
      if (!utils.isComponent(c)) {
        continue;
      }
      // messager.printMessage(Kind.NOTE, "2");

      TypeElement parent = getParentEitherComponent(c);
      if (parent != null) {
        result.put(c, parent);
      }
    }
    messager.printMessage(Kind.NOTE, "collectComponentToParentMapByDependencies result: " + result);

    return result;
  }

  /** Returns parent (sub)component if one exist, null otherwise. */
  private @Nullable TypeElement getParentEitherComponent(TypeElement c) {
    Preconditions.checkArgument(utils.isComponent(c));
    TypeElement parent = null;
    for (TypeElement dependency : getComponentDependencies(c)) {
      if (utils.isEitherComponent(dependency)) {
        if (parent != null) {
          messager.printMessage(
              Kind.ERROR, String.format("Found multiple parent (sub)components for %s.", c));
        }
        parent = dependency;
      }
    }
    return parent;
  }

  /**
   * Return mappings by checking Subcomponent factory method(provision method) in (sub)components.
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
            messager.printMessage(
                Kind.NOTE, "collectComponentToParentMapByFactoryMethod" + c + " method: " + e);
            if (utils.isSubcomponentProvisionMethod(e)
                || utils.isSubcomponentBuilderProvisionMethod(e)) {
              // messager.printMessage(Kind.NOTE, "1");
              TypeElement returnType = utils.getReturnTypeElement((ExecutableElement) e);
              if (utils.isSubcomponentBuilder(returnType)) {
                returnType = (TypeElement) returnType.getEnclosingElement();
              }

              // messager.printMessage(Kind.NOTE, "2");

              // found
              result.put(returnType, c);
            }
            return null;
          });
    }

    messager.printMessage(
        Kind.NOTE, TAG + ".collectComponentToParentMapByFactoryMethod: result" + result);
    return result;
  }

  private String getCoreInjectorPackage(TypeElement component) {
    return utils.getPackageString(component);
  }

  /**
   * Not all combination of components are allowed. Here are the limitation.
   *
   * <pre>
   *  1. All components must be scoped.
   * </pre>
   *
   * <pre>
   *  2. There is at most one dependencies component. This is automatically fulfilled for subcomponents.
   * </pre>
   *
   * <pre>
   *  3. Only one root. Forest not supported.
   * </pre>
   *
   * All exceptions are easy to fix, if any. This map to the core injectors very well. Modules
   * needed by a core injector but not needed by related component(s) can just be passed in as null
   * because it will never be used.
   */
  private void verifyComponents(Set<TypeElement> components) {
    TypeElement root = null;
    for (TypeElement component: components) {
      Preconditions.checkNotNull(utils.getScopeType(component, scopeAliasCondenser),
          String.format("Unscoped component supported : %s", component));
      AnnotationMirror annotationMirror = utils.getAnnotationMirror(component, Component.class);
      List<AnnotationValue> dependencies = null;
      AnnotationValue annotationValue = utils.getAnnotationValue(
          elements, annotationMirror, COMPONANT_ANNOTATION_ELEMENT_DEPENDENCIES);
      if (annotationValue != null) {
        dependencies = (List<AnnotationValue>) annotationValue.getValue();
      }
      if (dependencies == null || dependencies.isEmpty()) {
        Preconditions.checkState(root == null,
            String.format("More than one root components found: %s and %s", root, component));
        root = component;
      } else {
        Preconditions.checkState(dependencies.size() == 1,
            String.format("More than one dependencies found for component: %s", component));
      }
    }
  }

  /**
   * Generates Dagger component implementations that wrap around the core injectors.
   */
  private void generateWrapperComponents(Set<TypeElement> components) {
    componentToBuilderMap = collectComponentToBuilderMap();

    for (TypeElement component : components) {
      CoreInjectorInfo coreInjector = componentToCoreInjectorMap.get(component);
      // Ignore orphan components whose core injector is not generated.
      if (scopeSizer.getScopeSize(coreInjector) == -1) {
        continue;
      }

      ClassName coreInjectorClassName =
          getCoreInejectorClassName(coreInjectorPackage, coreInjector);
      String packageString = utils.getPackageString(component);
      String generatedComponentSimpleName =
          utils.getComponentImplementationSimpleNameFromInterface(component);
      messager.printMessage(
          Kind.NOTE,
          TAG + ".generateWrapperComponents: generated component " + generatedComponentSimpleName);
      TypeSpec.Builder componentBuilder =
          TypeSpec.classBuilder(generatedComponentSimpleName)
          .addModifiers(Modifier.PUBLIC)
          .addSuperinterface(TypeName.get(component.asType()))
          .superclass(coreInjectorClassName);

      // Ctor with parent component, component dependencies and modules as parameters.
      MethodSpec.Builder ctorBuilder =
          MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE);

      // Ctor parent component
      TypeElement parentComponent = componentToParentMap.get(component);
      StringBuilder callSuperStringBuilder = new StringBuilder("super(");
      boolean needLeadingComma = false;
      if (parentComponent != null) {
        needLeadingComma = true;
        ctorBuilder.addParameter(
            TypeName.get(parentComponent.asType()),
            utils.getSourceCodeName(parentComponent));
        // Cast the interface to implement which is a subclass of core injector therefore will be
        // accept by compiler.
        callSuperStringBuilder.append("($T) ").append(utils.getSourceCodeName(parentComponent));
      }

      // Ctor component dependencies parameters
      if (!coreInjectorToComponentDependencyMap.get(coreInjector).isEmpty()) {
        if (needLeadingComma) {
          callSuperStringBuilder.append(", ");
        }
        needLeadingComma = true;

        Set<TypeElement> deps = componentToComponentDependencyMap.get(component);
        for (TypeElement typeElement :
            utils.sortByFullName(coreInjectorToComponentDependencyMap.get(coreInjector))) {
          if (deps.contains(typeElement)) {
            ctorBuilder.addParameter(
                TypeName.get(typeElement.asType()), utils.getSourceCodeName(typeElement));
            callSuperStringBuilder.append(utils.getSourceCodeName(typeElement));
          } else {
            callSuperStringBuilder.append("null");
          }
          callSuperStringBuilder.append(", ");
        }
        callSuperStringBuilder.delete(
            callSuperStringBuilder.length() - 2, callSuperStringBuilder.length());
      }

      // Ctor @BindsInstance parameters
      if (!coreInjectorToBindsInstanceMap.get(coreInjector).isEmpty()) {
        if (needLeadingComma) {
          callSuperStringBuilder.append(", ");
        }
        needLeadingComma = true;

        Set<BindingKey> bindsInstances = componentToBindsInstanceMap.get(component);
        for (BindingKey key :
            utils.sortBindingKeys(coreInjectorToBindsInstanceMap.get(coreInjector))) {
          if (bindsInstances.contains(key)) {
            String sourceCodeName = utils.getSourceCodeNameHandlingBox(key, dependencies);
            ctorBuilder.addParameter(key.getTypeName(), sourceCodeName);
            callSuperStringBuilder.append(sourceCodeName);
          } else {
            callSuperStringBuilder.append("null");
          }
          callSuperStringBuilder.append(", ");
        }
        callSuperStringBuilder.delete(
            callSuperStringBuilder.length() - 2, callSuperStringBuilder.length());
      }

      // Ctor module parameters
      Set<TypeElement> allComponentModules = getAllModulesOfComponentRecursively(component);
      List<TypeElement> sortedComponentPassedModules =
          utils.sortByFullName(utils.getNonNullaryCtorOnes(allComponentModules));
      Set<TypeElement> coreInjectorPassedModules = new HashSet<>();
      coreInjectorPassedModules.addAll(scopedPassedModules.get(coreInjector));
      coreInjectorPassedModules.addAll(unscopedPassedModules);
      List<TypeElement> sortedCoreInjectorPassedModules =
          utils.sortByFullName(coreInjectorPassedModules);
      if (!sortedCoreInjectorPassedModules.isEmpty()) {
        if (needLeadingComma) {
          callSuperStringBuilder.append(", ");
        }
        needLeadingComma = true;
        for (TypeElement typeElement : sortedCoreInjectorPassedModules) {
          if (sortedComponentPassedModules.contains(typeElement)) {
            ctorBuilder.addParameter(TypeName.get(typeElement.asType()),
                utils.getSourceCodeName(typeElement));
            callSuperStringBuilder.append(utils.getSourceCodeName(typeElement));
          } else {
            callSuperStringBuilder.append("null");
          }
          callSuperStringBuilder.append(", ");
        }
        callSuperStringBuilder.delete(
            callSuperStringBuilder.length() - 2, callSuperStringBuilder.length());
      }
      callSuperStringBuilder.append(");");
      if (parentComponent != null) {
        CoreInjectorInfo coreInjectorInfo = componentToCoreInjectorMap.get(parentComponent);
        ctorBuilder.addCode(
            callSuperStringBuilder.toString(),

            // TODO: hack, restore
            getCoreInejectorClassName(coreInjectorPackage, coreInjectorInfo));
        // ClassName.get(
        //     utils.getPackageString(parentComponent),
        //     utils.getComponentImplementationSimpleNameFromInterface(parentComponent)));

      } else {
        ctorBuilder.addCode(callSuperStringBuilder.toString());
      }
      componentBuilder.addMethod(ctorBuilder.build());

      // Generate provision method

      // Generate Builder.
      @Nullable TypeElement explicitBuilder = componentToBuilderMap.get(component);
      generateWrapperComponentBuilder(
          component,
          parentComponent,
          sortedComponentPassedModules,
          componentBuilder,
          coreInjector,
          explicitBuilder);

      // Generate builder().
      ClassName builderClassName =
          ClassName.get(
              packageString,
              generatedComponentSimpleName,
              explicitBuilder != null ? explicitBuilder.getSimpleName().toString() : "Builder");
      MethodSpec.Builder builderMethodSpecBuilder =
          MethodSpec.methodBuilder("builder")
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
              .returns(builderClassName);
      boolean isSubcomponent = utils.isSubcomponent(component);
      if (isSubcomponent) {
        builderMethodSpecBuilder.addParameter(
            ClassName.get(componentToParentMap.get(component).asType()), "v");
      }
      builderMethodSpecBuilder.addCode(
          "return new $T($L);", builderClassName, isSubcomponent ? "v" : "");
      componentBuilder.addMethod(builderMethodSpecBuilder.build());

      JavaFile javaFile =
          JavaFile.builder(packageString, componentBuilder.build()).build();
      try {
        messager.printMessage(Kind.NOTE,
            String.format("%s: writing java file: %s", TAG, javaFile.toJavaFileObject().getName()));
        javaFile.writeTo(processingEnv.getFiler());
      } catch (IOException e) {
        // messager.printMessage(Kind.ERROR, e.toString());
      }
    }
  }

  private Map<TypeElement, TypeElement> collectComponentToBuilderMap() {
    Map<TypeElement, TypeElement> result = new HashMap<>();

    for (TypeElement builder : componentBuilders) {
      // messager.printMessage(Kind.NOTE, TAG + "collectComponentToBuilderMap builder: " + builder);
      // ExecutableElement buildMethod =
      //     Preconditions.checkNotNull(
      //         utils.getBuildMethod(processingEnv.getTypeUtils(), elements, builder),
      //         "build method not found for builder: " + builder);
      // TypeElement returnType = Preconditions.checkNotNull(utils.getReturnTypeElement(buildMethod));
      // TODO: clean above.
      result.put((TypeElement) builder.getEnclosingElement(), builder);
    }

    messager.printMessage(Kind.NOTE, TAG + "collectComponentToBuilderMap result: " + result);
    return result;
  }

  private Set<TypeElement> getAllModulesOfComponentRecursively(TypeElement component) {
    Set<TypeElement> result = new HashSet<>();
    AnnotationMirror componentAnnotationMirror =
        utils.isComponent(component)
            ? utils.getAnnotationMirror(component, Component.class)
            : utils.getAnnotationMirror(component, Subcomponent.class);
    AnnotationValue moduleAnnotationValue =
        utils.getAnnotationValue(elements, componentAnnotationMirror, "modules");
    if (moduleAnnotationValue != null) {
      for (AnnotationValue annotationValue :
          (List<AnnotationValue>) moduleAnnotationValue.getValue()) {
        result.add((TypeElement) ((DeclaredType) annotationValue.getValue()).asElement());
      }
    }
    result = utils.findAllModulesRecursively(result, elements);
    return result;
  }

  /**
   * Subcomponents has 1 ctor parameter, which is the parent (sub)component. Components has 0 ctor
   * parameter and 0 or more dependecies.
   */
  private void generateWrapperComponentBuilder(
      TypeElement component,
      @Nullable TypeElement parentComponent,
      List<TypeElement> sortedPassedModules,
      TypeSpec.Builder componentBuilder,
      CoreInjectorInfo coreInjector,
      @Nullable TypeElement expliciteBuilder) {
    int pos = 0;
    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, component: "
            + component
            + " explicit: "
            + (expliciteBuilder != null));
    Preconditions.checkArgument(utils.isComponent(component) != utils.isSubcomponent(component));
    boolean explicit = expliciteBuilder != null;
    boolean isSubcomponent = utils.isSubcomponent(component);

    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, pos: 1");

    String packageString = utils.getPackageString(component);
    String generatedComponentSimpleName =
        utils.getComponentImplementationSimpleNameFromInterface(component);
    ClassName componentClassName = ClassName.get(packageString,
        generatedComponentSimpleName);

    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, pos: 2");
    // Generate class header.
    String builderName = explicit ? expliciteBuilder.getSimpleName().toString() : "Builder";
    TypeSpec.Builder builderBuilder =
        TypeSpec.classBuilder(builderName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC);
    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, pos: 3");
    if (explicit) {
      ElementKind kind = expliciteBuilder.getKind();
      ClassName superName = ClassName.get(component).nestedClass(builderName);
      if (kind.equals(ElementKind.INTERFACE)) {
        builderBuilder.addSuperinterface(superName);
      } else {
        Preconditions.checkState(
            kind.equals(ElementKind.CLASS),
            TAG + " unexpected kind for builder: " + expliciteBuilder);
        builderBuilder.superclass(superName);
      }
    }

    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, pos: 4");
    // ctor for subcomponent.
    if (isSubcomponent) {
      TypeName parentComponentTypeName = TypeName.get(componentToParentMap.get(component).asType());
      String parentComponentSourceCodeName = utils.getSourceCodeName(parentComponentTypeName);
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
      if (explicit && parentComponent != null) {
        dependencyClassName = (ClassName) ClassName.get(parentComponent.asType());
        String methodName =
            explicit
                ? utils.getBuilderSetterName(types, elements, expliciteBuilder, parentComponent)
                : null;
        if (methodName != null || !explicit) {
          utils.addSetMethod(
              types, elements, componentClassName, builderBuilder, dependencyClassName, methodName, builderName);
        }
      }
    }

    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, pos: 5");
    /**
     * Set deps methods.
     */
    for (TypeElement m : utils.sortByFullName(componentToComponentDependencyMap.get(component))) {
      String methodName = explicit
          ? Preconditions.checkNotNull(
          utils.getBuilderSetterName(types, elements, expliciteBuilder, m))
          : null;

      messager.printMessage(Kind.NOTE, TAG + " generateWrapperComponentBuilder deps " + m);
      utils.addSetMethod(
          types, elements, componentClassName,
          builderBuilder,
          (ClassName) ClassName.get(m.asType()),
          methodName,
          builderName);
    }

    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, pos: 6");
    /** Set @BindsInstance methods. */
    /**
     * TODO: refactor this. see {@link #collectComponentToBindsInstanceMap()}
     */
    java.util.Set<BindingKey> instanceDeps =
        Sets.newHashSet(componentToBindsInstanceMap.get(component));
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
        Kind.NOTE, TAG + ".generateWrapperComponentBuilder instanceDeps" + instanceDeps);
    for (BindingKey key : utils.sortBindingKeys(instanceDeps)) {
      String methodName = explicit
          ? Preconditions.checkNotNull(
          utils.getBuilderSetterName(types, elements, expliciteBuilder, key))
          : null;
      ClassName builderParentClassName = componentClassName;
      if (explicit) {
        ExecutableElement setter =
            utils.getBuilderSetter(types, elements, expliciteBuilder, key);
        if (setter.getReturnType().getKind() == TypeKind.VOID) {
          builderParentClassName = null;
        }
      }

      messager.printMessage(Kind.NOTE, TAG + " generateWrapperComponentBuilder @BindsInstance " + key);
      utils.addSetMethod(
          types, elements, builderParentClassName,
          builderBuilder,
          key.getTypeName(),
          methodName,
          builderName);
    }
    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, pos: 7");

    /**
     * Set module methods.
     */
    for (TypeElement m : sortedPassedModules) {
      String methodName = explicit
          ? Preconditions.checkNotNull(
          utils.getBuilderSetterName(types, elements, expliciteBuilder, m))
          : null;

      messager.printMessage(Kind.NOTE, TAG + " generateWrapperComponentBuilder module " + m);
      utils.addSetMethod(
          types, elements, componentClassName,
          builderBuilder,
          (ClassName) ClassName.get(m.asType()),
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
    boolean needLeadingComma = false;
    if (parentComponent != null) {
      needLeadingComma = true;
      returnCodeBuilder.append(utils.getSourceCodeName(parentComponent));
    }

    // return statement deps params
    Set<TypeElement> deps = componentToComponentDependencyMap.get(component);
    if (!deps.isEmpty()) {
      if (needLeadingComma) {
        returnCodeBuilder.append(", ");
      }
      needLeadingComma = true;
      for (TypeElement dep : utils.sortByFullName(deps)) {
        returnCodeBuilder.append(utils.getSourceCodeName(dep)).append(", ");
      }
      returnCodeBuilder.delete(returnCodeBuilder.length() - 2, returnCodeBuilder.length());
    }

    // return statement @BindsInstance params
    Set<BindingKey> bindsInstances = componentToBindsInstanceMap.get(component);
    if (!bindsInstances.isEmpty()) {
      if (needLeadingComma) {
        returnCodeBuilder.append(", ");
      }
      needLeadingComma = true;
      for (BindingKey key: utils.sortBindingKeys(bindsInstances)) {
        returnCodeBuilder.append(utils.getSourceCodeNameHandlingBox(key, dependencies)).append(", ");
      }
      returnCodeBuilder.delete(returnCodeBuilder.length() - 2, returnCodeBuilder.length());
    }

    // return statement module params
    if (!sortedPassedModules.isEmpty()) {
      if (needLeadingComma) {
        returnCodeBuilder.append(", ");
      }
      needLeadingComma = true;
      for (TypeElement module : sortedPassedModules) {
        String moduleFiledName = utils.getSourceCodeName(module);
        returnCodeBuilder.append(moduleFiledName).append(", ");
      }
      returnCodeBuilder.delete(returnCodeBuilder.length() - 2, returnCodeBuilder.length());
    }
    returnCodeBuilder.append(");");
    messager.printMessage(
        Kind.NOTE,
        "generateWrapperComponentBuilder, return string: " + returnCodeBuilder.toString());
    buildMethodBuilder.addCode(returnCodeBuilder.toString(), componentClassName);
    builderBuilder.addMethod(buildMethodBuilder.build());

    componentBuilder.addType(builderBuilder.build());
  }


  public ClassName getCoreInejectorClassName(String coreInjectorPackage, CoreInjectorInfo coreInjectorInfo) {
    return ClassName.get(coreInjectorPackage, coreInjectorGenerator.getTopLevelInjectorName(
        coreInjectorInfo, topLevelInjectorPrefix, topLevelInjectorSuffix));
  }

  /**
   * The injector tree with on injector for each scope. The map is from child to parent.
   */
  private Map<CoreInjectorInfo, CoreInjectorInfo> getCoreInjectorTree(Set<TypeElement> components) {
    Map<CoreInjectorInfo, CoreInjectorInfo> result = new HashMap<>();
    for (TypeElement component : componentToCoreInjectorMap.keySet()) {
      CoreInjectorInfo coreInjectorInfo = componentToCoreInjectorMap.get(component);
      TypeElement parentComponent = componentToParentMap.get(component);
      if (parentComponent == null) {
        continue;
      }
      CoreInjectorInfo parentCoreInjectorInfo = componentToCoreInjectorMap.get(parentComponent);
      if (parentCoreInjectorInfo == null || parentCoreInjectorInfo.equals(coreInjectorInfo)) {
        continue;
      }
      result.put(coreInjectorInfo, parentCoreInjectorInfo);
    }

    return result;
  }

  /**
   * Returns dependencies of the give component, empty set if none.
   */
  private Set<TypeElement> getComponentDependencies(TypeElement component) {
    Set<TypeElement> result = new HashSet<>();
    AnnotationMirror componentAnnotationMirror =
        Preconditions.checkNotNull(utils.getAnnotationMirror(component, Component.class),
            String.format("@Component not found for %s", component));

    AnnotationValue dependenciesAnnotationValue = utils.getAnnotationValue(
        elements, componentAnnotationMirror, COMPONANT_ANNOTATION_ELEMENT_DEPENDENCIES);
    if (dependenciesAnnotationValue == null) {
      return result;
    }
    List<? extends AnnotationValue> dependencies =
        (List<? extends AnnotationValue>) dependenciesAnnotationValue.getValue();
    for (AnnotationValue dependency : dependencies) {
      result.add((TypeElement) ((DeclaredType) dependency.getValue()).asElement());
    }
    return result;
  }

  private SetMultimap<CoreInjectorInfo, TypeElement> getCoreInjectorToComponentMap(
      Set<TypeElement> components) {
    SetMultimap<CoreInjectorInfo, TypeElement> result = HashMultimap.create();
    for (Map.Entry<TypeElement, CoreInjectorInfo> entry : componentToCoreInjectorMap.entrySet()) {
      result.put(entry.getValue(), entry.getKey());
    }

    if (!coreInjectorTree.isEmpty()) {
      messager.printMessage(
          Kind.NOTE,
          "getMappedMembersInjectors. coreInjectorTree: "
              + coreInjectorTree
              + " mapping: "
              + componentToCoreInjectorMap);
      // TODO: remove this?
      for (TypeElement typeElement :
          Sets.difference(components, componentToCoreInjectorMap.keySet())) {
        result.put(rootCoreInjectorInfo, typeElement);
      }
    }
    return result;
  }

  /**
   * Return the explicit scopes and implicit scopes of give components. Those with neither explicit
   * or implicit scope are excluded.
   */
  private Map<TypeElement, CoreInjectorInfo> collectComponentToCoreInjectorMap(
      Set<TypeElement> components) {
    Map<TypeElement, CoreInjectorInfo> componentScopeMap = new HashMap<>();
    for (TypeElement component : components) {
      TypeElement scope = getScopeForComponent(component, messager);
      if (scope != null) {
        componentScopeMap.put(component, new CoreInjectorInfo(scope));
      } else {
        messager.printMessage(
            Kind.ERROR, String.format("(Sub)Component %s without scope.", component));
      }
    }
    messager.printMessage(
        Kind.NOTE,
        TAG + ".collectComponentToCoreInjectorMap: result " + componentScopeMap);
    return componentScopeMap;
  }

  /**
   * Returns scope for the give dagger Component, null is unscoped. The result is either
   * explicitly specified or implicitly inherited from one of the ancestors.
   */
  @Nullable
  private TypeElement getScopeForComponent(TypeElement component,
      Messager messager) {
    DeclaredType scope = utils.getScopeType(component, scopeAliasCondenser);
    if (scope != null) {
      return (TypeElement) scope.asElement();
    }
    AnnotationMirror componentAnnotationMirror =
        utils.getAnnotationMirror(component, Component.class);
    List<AnnotationValue> dependencies =
        componentAnnotationMirror == null
            ? null
            : (List<AnnotationValue>)
                utils.getAnnotationValue(elements, componentAnnotationMirror, "dependencies");
    if (dependencies == null) {
      return null;
    }
    Set<TypeElement> parentScopes = new HashSet<>();
    for (AnnotationValue dependency : dependencies) {
      DeclaredType dependencyClass = (DeclaredType) dependency.getValue();
      TypeElement parentScope = getScopeForComponent((TypeElement) dependencyClass.asElement(),
          messager);
      if (parentScope != null) {
        parentScopes.add(parentScope);
      }
    }
    if (parentScopes.isEmpty()) {
      return null;
    }
    if (parentScopes.size() > 1) {
      messager.printMessage(Kind.ERROR,
          String.format(
              "Component %s depends on more than one scoped components. The scopes are: %s",
              component, parentScopes));
    }
    return Iterables.getOnlyElement(parentScopes);
  }

  private void getModulesInComponents(Collection<? extends Element> components,
      SetMultimap<CoreInjectorInfo, TypeElement> scopeModules, Set<TypeElement> unscopedModules) {
    Set<TypeElement> modules = new HashSet<>();
    for (Element component : components) {
      AnnotationMirror componentAnnotationMirror = utils.isComponent(component) ?
          utils.getAnnotationMirror(component, Component.class) :
          utils.getAnnotationMirror(component, Subcomponent.class);
      AnnotationValue moduleAnnotationValue = Utils
          .getAnnotationValue(elements, componentAnnotationMirror, "modules");
      if (moduleAnnotationValue != null) {
        for (AnnotationValue annotationValue :
            (List<AnnotationValue>) moduleAnnotationValue.getValue()) {
          modules.add((TypeElement) ((DeclaredType) annotationValue.getValue()).asElement());
        }
      }
    }
    modules = utils.findAllModulesRecursively(modules, elements);
    for (TypeElement module : modules) {
      TypeElement scopeType = utils.getModuleScope((DeclaredType) module.asType(),
          scopeAliasCondenser);
      if (scopeType != null) {
        scopeModules.put(new CoreInjectorInfo(scopeType), module);
      } else {
        unscopedModules.add(module);
      }
    }
  }

  private void verifyScopeTree(Map<TypeElement, TypeElement> childToParentMap) {
    Set<TypeElement> all = Sets.newHashSet(childToParentMap.keySet());
    all.addAll(childToParentMap.values());
    for (TypeElement typeElement : all) {
      Preconditions.checkState(
          utils.isScopeTypeElement(typeElement),
          String.format("Scope %s does not have @Scope annotation", typeElement));
    }
  }

  private TypeElement getTypeElementFrom(AnnotationValue annotationValue) {
    return (TypeElement) ((DeclaredType) annotationValue.getValue()).asElement();
  }


  private Component getComponentForInjectionScope(DeclaredType scopeType) {
//    Element scopeTypeElement = scopeType.asElement();
//    for (Component component : Component.values()) {
//      if (elements
//          .getTypeElement(component.injectionScope.getCanonicalName())
//          .equals(scopeTypeElement)) {
//        return component;
//      }
//    }
    throw new RuntimeException(String.format("Did not find component for scope %s", scopeType));
  }

  /**
   * Returns the {@link DeclaredType} of the scope class of the
   * {@link MembersInjector} specified.
   */
  private DeclaredType getMembersInjectorScope(DeclaredType membersInjectorType) {
    ExecutableElement scopeElement = null;
    TypeElement membersInjectorTypeElement =
        elements.getTypeElement(MembersInjector.class.getCanonicalName());
    for (Element element : membersInjectorTypeElement.getEnclosedElements()) {
      if (element.getSimpleName().contentEquals("scope")) {
        scopeElement = (ExecutableElement) element;
      }
    }

    Preconditions.checkNotNull(scopeElement);

    for (AnnotationMirror annotationMirror :
        membersInjectorType.asElement().getAnnotationMirrors()) {
      if (annotationMirror
          .getAnnotationType()
          .asElement()
          .equals(elements.getTypeElement(MembersInjector.class.getCanonicalName()))) {
        return (DeclaredType) annotationMirror.getElementValues().get(scopeElement).getValue();
      }
    }

    throw new RuntimeException(
        String.format("Scope not found for MembersInjector: %s", membersInjectorType));
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Sets.newHashSet(
        // ContributesAndroidInjector.class.getCanonicalName(),
        Component.class.getCanonicalName()//,
        // Subcomponent.class.getCanonicalName(),
        // Component.Builder.class.getCanonicalName(),
 //       Subcomponent.Builder.class.getCanonicalName()
    );
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}
