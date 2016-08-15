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
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic.Kind;

/**
 * Annotation processor to generate tiger injectors. The package of generated injectors are
 * specified by {@link PackageForGenerated}. Names of the injectors are specified by {@link
 * ScopedComponentNames}. Dependencies of the injectors are specified by {@link ScopeDependency}.
 * The injectors can be instantiated by builder. Here is an example.
 * <pre>
 * DaggerApplicationComponent applicationComponent = new DaggerApplicationComponent.Builder().build();
 * DaggerActivityComponent activityComponent =
 * new DaggerActivityComponent.Builder().daggerApplicationComponent(applicationComponent).build();
 *
 * </pre>
 */
@AutoService(Processor.class)
public class ComponentGeneratorProcessor extends AbstractProcessor {
  private static final String TAG = "ComponentGeneratorProcessor";

  private Gson gson = new Gson();

  private ProcessingEnvironment env;
  private Elements elementUtils;
  private Messager messager;

  // Following could be used cross processing rounds.
  private SetMultimap<ComponentInfo, TypeElement> modules = LinkedHashMultimap.create();
  private SetMultimap<ComponentInfo, TypeElement> injections = LinkedHashMultimap.create();
  private SetMultimap<ComponentInfo, TypeElement> ctorInjectedClasses = LinkedHashMultimap.create();
  private Set<TypeElement> unscopedModules = new HashSet<>();
  private Map<ComponentInfo, ComponentInfo> componentTree;
  private NewScopeSizer scopeSizer;
  private Map<TypeElement, ComponentInfo> scopeToComponent;
  private String packageForGenerated;
  
  private boolean done;
  
  // Could be recovered in next round.
  private List<String> allRecoverableErrors = new ArrayList<>();

  @Override
  public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    
    this.env = env;
    elementUtils = env.getElementUtils();
    messager = env.getMessager();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
    if (done) {
      return false;
    }

    System.out.println("pure tiger");

    PackageElement packageElement =
        elementUtils.getPackageElement(SharedNames.DEPENDENCY_INFORMATION_PACKAGE_NAME);
    if (packageElement == null) {
      return false;
    }

    if (componentTree == null) {
      Map<TypeElement, TypeElement> scopeTree = null;
      Map<TypeElement, String> scopedComponentNames = null;
      for (Element element : packageElement.getEnclosedElements()) {
        for (Element e : element.getEnclosedElements()) {
          if (e.getKind().equals(ElementKind.FIELD)) {
            Preconditions.checkState(
                ((DeclaredType) e.asType())
                    .asElement()
                    .equals(elementUtils.getTypeElement(String.class.getCanonicalName())),
                String.format("There should be only fields of type String. But got field: %s", e));

            TypeElement typeElement;
            java.lang.reflect.Type stringListType = new TypeToken<List<String>>() {}.getType();
            List<String> collected;

            String fieldName = e.getSimpleName().toString();
            String jsonString = (String) ((VariableElement) e).getConstantValue();
            if (fieldName.equals(
                SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_SCOPE_DEPENDENCIES)) {
              collected = gson.fromJson(jsonString, stringListType);
              if (collected.isEmpty()) {
                continue;
              }
              Set<TypeElement> scopeDependencies = new HashSet<>();
              for (String elementName : collected) {
                typeElement = elementUtils.getTypeElement(elementName);
                scopeDependencies.add(typeElement);
              }
              if (scopeTree != null) {
                messager.printMessage(
                    Kind.ERROR,
                    String.format(
                        "Duplicate scopeDependencies. Existing: %s, new: %s",
                        scopeTree,
                        scopeDependencies));
              }
              scopeTree = getScopeTree(scopeDependencies);
            } else if (fieldName.equals(
                SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_SCOPED_COMPONENT_NAMES)) {
              collected = gson.fromJson(jsonString, stringListType);
              if (collected.isEmpty()) {
                continue;
              }
              Set<TypeElement> scopeComponentNameElements = new HashSet<>();
              for (String elementName : collected) {
                typeElement = elementUtils.getTypeElement(elementName);
                scopeComponentNameElements.add(typeElement);
              }
              scopedComponentNames = getScopedComponentNames(scopeComponentNameElements);
            } else if (fieldName.equals(
                SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_PACKAGE_FOR_GENERATED)) {
              packageForGenerated = jsonString;
            }
          } // Enclosed elements.
        } // Classes
      }
    Preconditions.checkNotNull(packageForGenerated);

    if (scopedComponentNames == null) {
      Set<TypeElement> allScopes = new HashSet<>();
      Preconditions.checkNotNull(scopeTree);
      allScopes.addAll(scopeTree.values());
      allScopes.addAll(scopeTree.keySet());
      scopedComponentNames = getDefaultScopedComponentNames(allScopes);
    }

    componentTree = getComponentTree(scopeTree, scopedComponentNames);
    if (componentTree.isEmpty()) {
      // TODO(freeman): support only scope.
    }
    scopeSizer = new TreeScopeSizer(componentTree, null);
//    messager.printMessage(Kind.NOTE, String.format("%s componentTree: %s", TAG, componentTree));
//    messager.printMessage(Kind.NOTE, String.format("%s scopeSizer: %s", TAG, scopeSizer));
    scopeToComponent = getScopeToComponentMap();
    }
    if (componentTree == null) {
      return false;
    }

    
    for (Element element : packageElement.getEnclosedElements()) {
      for (Element e : element.getEnclosedElements()) {
        if (e.getKind().equals(ElementKind.FIELD)) {
          Preconditions.checkState(
              ((DeclaredType) e.asType())
                  .asElement()
                  .equals(elementUtils.getTypeElement(String.class.getCanonicalName())),
              String.format("There should be only fields of type String. But got field: %s", e));

          TypeElement typeElement;
          DeclaredType elementType;
          java.lang.reflect.Type stringListType = new TypeToken<List<String>>() {}.getType();
          List<String> collected;

          String fieldName = e.getSimpleName().toString();
          String jsonString = (String) ((VariableElement) e).getConstantValue();
          if (fieldName.equals(SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_MODULES)) {
            collected = gson.fromJson(jsonString, stringListType);
            for (String elementName : collected) {
              typeElement = elementUtils.getTypeElement(elementName);
              elementType = (DeclaredType) typeElement.asType();
              if (Utils.hasProvisionMethod(elementType)) {
                TypeElement scope = Utils.getModuleScope(elementType);
                if (scope == null) {
                  unscopedModules.add(typeElement);
                } else {
                  modules.put(scopeToComponent.get(scope), typeElement);
                }
              }
            }
          } else if (fieldName.equals(
              SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_MEMBERS_INJECTORS)) {
            collected = gson.fromJson(jsonString, stringListType);
            for (String elementName : collected) {
              typeElement = elementUtils.getTypeElement(elementName);
              elementType = (DeclaredType) typeElement.asType();
              DeclaredType scopeClass = getMembersInjectorScope(elementType);
              TypeElement scope = (TypeElement) scopeClass.asElement();
              injections.put(scopeToComponent.get(scope), typeElement);
            }
          } else if (fieldName.equals(
              SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_SCOPE_DEPENDENCIES)) {
            // Already handled
          } else if (fieldName.equals(
              SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_SCOPED_COMPONENT_NAMES)) {
            // Already handled
          } else if (fieldName.equals(
              SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_PACKAGE_FOR_GENERATED)) {
            // Already handled
          } else if (fieldName.equals(
              SharedNames.DEPENDENCY_INFORMATION_FIELD_NAME_CTOR_INJECTED_CLASSES)) {
            collected = gson.fromJson(jsonString, stringListType);
            for (String elementName : collected) {
              typeElement = elementUtils.getTypeElement(elementName);
              // typeElement must have scope. See {@link DependencyInformationCollectorProcessor}.
              TypeElement scope = (TypeElement) Utils.getScopeType(typeElement).asElement();
              ctorInjectedClasses.put(scopeToComponent.get(scope), typeElement);
            }
          } else {
            throw new RuntimeException(String.format("Unexpected field: %s", fieldName));
          }
        }
      } // Enclosed elements.
    } // Classes

    check();

    if (!allRecoverableErrors.isEmpty()) {
      if (env.processingOver()) {
        for (String err : allRecoverableErrors) {
          messager.printMessage(Kind.ERROR, err);
        }
      }
      // Leave it to next round.
      return false;
    }

    Set<TypeElement> allModules = Sets.newHashSet(modules.values());
    allModules.addAll(unscopedModules);
    NewDependencyCollector dependencyCollector = new NewDependencyCollector(processingEnv);
    Collection<TypeElement> membersInjectors = injections.values();
    Collection<NewDependencyInfo> dependencyInfos =
        dependencyCollector.collect(allModules, membersInjectors, allRecoverableErrors);

    Set<NewBindingKey> requiredKeys =
        dependencyCollector.getRequiredKeys(membersInjectors, dependencyInfos);
    //messager.printMessage(Kind.NOTE, String.format("all keys required: %s", requiredKeys));

    NewScopeCalculator newScopeCalculator =
        new NewScopeCalculator(scopeSizer, dependencyInfos, requiredKeys, processingEnv);
    Preconditions.checkState(newScopeCalculator.initialize().isEmpty());
    NewInjectorGenerator newInjectorGenerator =
        new NewInjectorGenerator(
            NewDependencyCollector.collectionToMultimap(dependencyInfos),
            newScopeCalculator,
            modules,
            unscopedModules,
            injections,
            componentTree,
            null, // TODO(freeman): fill it if componentTree is empty.
            packageForGenerated,
            "Dagger",
            "Component",
            processingEnv);
    newInjectorGenerator.generate();

    done = true;
    
    return false;
  }

  private Map<TypeElement, ComponentInfo> getScopeToComponentMap() {
    Map<TypeElement, ComponentInfo> result = new HashMap<>();

    Set<ComponentInfo> allComponentInfos = new HashSet<>();
    allComponentInfos.addAll(componentTree.values());
    allComponentInfos.addAll(componentTree.keySet());
    for (ComponentInfo componentInfo : allComponentInfos) {
      result.put(componentInfo.getScope(), componentInfo);
    }
    return result;
  }

  /**
   * Creates map from child to parent.
   */
  private Map<ComponentInfo, ComponentInfo> getComponentTree(
      Map<TypeElement, TypeElement> scopeTree, Map<TypeElement, String> scopesComponentNames) {
    Map<ComponentInfo, ComponentInfo> result = new HashMap<>();
    for (Map.Entry<TypeElement, TypeElement> entry : scopeTree.entrySet()) {
      TypeElement child = entry.getKey();
      TypeElement parent = entry.getValue();
      result.put(
          new ComponentInfo(child, scopesComponentNames.get(child)),
          new ComponentInfo(parent, scopesComponentNames.get(parent)));
    }
    return result;
  }

  /**
   * Component derived from scope.
   */
  private Map<TypeElement, String> getDefaultScopedComponentNames(Set<TypeElement> allScopes) {
    Map<TypeElement, String> result = new HashMap<>();
    for (TypeElement typeElement : allScopes) {
      result.put(typeElement, typeElement.getQualifiedName().toString().replace(".", "_"));
    }
    return result;
  }

  private Map<TypeElement, String> getScopedComponentNames(
      Set<TypeElement> scopeComponentNameElements) {
    Map<TypeElement, String> result = new HashMap<>();
    for (TypeElement typeElement : scopeComponentNameElements) {
      AnnotationMirror annotationMirror =
          Utils.getAnnotationMirror(typeElement, ScopedComponentNames.class);
      TypeElement scope =
          (TypeElement)
              ((DeclaredType) Utils.getAnnotationValue(annotationMirror, "scope").getValue())
                  .asElement();
      String name = (String) Utils.getAnnotationValue(annotationMirror, "name").getValue();
      Preconditions.checkState(
          result.put(scope, name) == null,
          String.format(
              "Duplicate @ScopeComponentName for scope: %s. All elements: %s",
              scope,
              scopeComponentNameElements));
    }
    return result;
  }

  /**
   * Creates a map from child to parent from the give {@link TypeElement}s, which are annotated with
   * {@link ScopeDependency}.
   */
  private Map<TypeElement, TypeElement> getScopeTree(Set<TypeElement> scopeDependencies) {
    Map<TypeElement, TypeElement> result = new HashMap<>();
    for (TypeElement element : scopeDependencies) {
      AnnotationMirror annotationMirror =
          Preconditions.checkNotNull(
              Utils.getAnnotationMirror(element, ScopeDependency.class),
              String.format("Did not find @ScopeDependency on %s", element));
      TypeElement scope =
          (TypeElement)
              ((DeclaredType) Utils.getAnnotationValue(annotationMirror, "scope").getValue())
                  .asElement();
      TypeElement parent =
          (TypeElement)
              ((DeclaredType) Utils.getAnnotationValue(annotationMirror, "parent").getValue())
                  .asElement();
      Preconditions.checkState(
          result.put(scope, parent) == null,
          String.format(
              "Duplicate ScopeDependencies found for %s. All dependencies are: %s",
              scope,
              scopeDependencies));
    }
    verifyScopeTree(result);
    return result;
  }

  private void verifyScopeTree(Map<TypeElement, TypeElement> childToParentMap) {
    Set<TypeElement> all = Sets.newHashSet(childToParentMap.keySet());
    all.addAll(childToParentMap.values());
    for (TypeElement typeElement : all) {
      Preconditions.checkState(
          Utils.isScopeTypeElement(typeElement),
          String.format("Scope %s does not have @Scope annotation", typeElement));
    }
  }

  private void checkScopes(
      Collection<NewDependencyInfo> deps,
      Set<NewBindingKey> requiredKeys,
      ProcessingEnvironment env) {
    NewScopeCalculator newScopeCalculator =
        new NewScopeCalculator(scopeSizer, deps, requiredKeys, env);
    allRecoverableErrors.addAll(newScopeCalculator.initialize());
    if (!allRecoverableErrors.isEmpty()) {
      return;
    }
    checkScopedSetBindings(deps, newScopeCalculator);
  }

  /**
   * Checks if there are duplicated bindings for the a key.
   */
  private void checkDuplicateBindings(Collection<NewDependencyInfo> deps) {
    SetMultimap<NewBindingKey, NewDependencyInfo> map =
        NewDependencyCollector.collectionToMultimap(deps);
    for (NewBindingKey key : map.keySet()) {
      Set<NewDependencyInfo> dependencies = map.get(key);
      if (dependencies.size() == 1) {
        continue;
      }
      for (NewDependencyInfo info : dependencies) {
        if (info.isUnique()) {
          messager.printMessage(
              Kind.ERROR,
              String.format(
                  "Key %s has multiple bindings including unique type one(s). Bindings found: %s",
                  key,
                  dependencies));
          break;
        }
      }
    }
  }

  private void check() {
    Set<TypeElement> allModules = Sets.newHashSet(modules.values());
    allModules.addAll(unscopedModules);
    NewDependencyCollector dependencyCollector = new NewDependencyCollector(processingEnv);
    Collection<TypeElement> membersInjectors = injections.values();
    Collection<NewDependencyInfo> dependencyInfos =
        dependencyCollector.collect(allModules, membersInjectors, allRecoverableErrors);
    if (!allRecoverableErrors.isEmpty()) {
      return;
    }
    checkDuplicateBindings(dependencyInfos);
    Set<NewBindingKey> requiredKeys =
        dependencyCollector.getRequiredKeys(membersInjectors, dependencyInfos);
    checkScopes(dependencyInfos, requiredKeys, env);
  }

  /**
   * Scoped set bindings are dangerous.
   */
  private void checkScopedSetBindings(
      Collection<NewDependencyInfo> dependencyInfos, NewScopeCalculator scopeCalculator) {
    Set<NewBindingKey> explicitScopedKeys = scopeCalculator.getExplicitScopedKeys();
    for (NewDependencyInfo info : dependencyInfos) {
      if (info.isSet() && explicitScopedKeys.contains(info.getDependant())) {
        allRecoverableErrors.add(
            String.format("Set binding should not be scoped. Binding: %s", info));
      }
    }
  }

  /**
   * Returns the {@link DeclaredType} of the scope class of the
   * {@link MembersInjector} specified.
   */
  private DeclaredType getMembersInjectorScope(DeclaredType membersInjectorType) {
    ExecutableElement scopeElement = null;
    TypeElement membersInjectorTypeElement =
        elementUtils.getTypeElement(MembersInjector.class.getCanonicalName());
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
          .equals(elementUtils.getTypeElement(MembersInjector.class.getCanonicalName()))) {
        return (DeclaredType) annotationMirror.getElementValues().get(scopeElement).getValue();
      }
    }

    throw new RuntimeException(
        String.format("Scope not found for MembersInjector: %s", membersInjectorType));
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Sets.newHashSet(GenerationTriggerAnnotation.class.getCanonicalName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}
