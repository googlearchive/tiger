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
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Collections2;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.Component;
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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic.Kind;

/**
 * Collects Dagger Component interfaces and generate Dagger Component implementions by wraping about
 * tiger injectors.
 */
@AutoService(Processor.class)
public class TigerDaggerGeneratorProcessor extends AbstractProcessor {
  private static final String TAG = "TigerDaggerGeneratorProcessor";

  private String coreInjectorPackage;
  private Elements elementUtils;
  private Messager messager;

  private Map<ComponentInfo, ComponentInfo> coreInjectorTree;
  private ComponentInfo rootComponentInfo;
  private NewScopeSizer scopeSizer;
  private SetMultimap<ComponentInfo, TypeElement> scopedModules = HashMultimap.create();
  private Set<TypeElement> unscopedModules = new HashSet<>();
  private SetMultimap<ComponentInfo, TypeElement> scopedPassedModules = HashMultimap.create();
  private Set<TypeElement> unscopedPassedModules = new HashSet<>();

  private List<String> allRecoverableErrors = new ArrayList<>();
  
  private boolean done;

  private NewInjectorGenerator newInjectorGenerator;

  @Override
  public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    
    elementUtils = env.getElementUtils();
    messager = env.getMessager();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
    messager.printMessage(Kind.NOTE, String.format("%s: process()", TAG));
    if (done) {
      return false;
    }
    
    Set<TypeElement> components =
        new HashSet<>(Collections2.transform(env.getElementsAnnotatedWith(Component.class),
            new Function<Element, TypeElement>() {
              @Override
              public TypeElement apply(Element from) {
                return (TypeElement) from;
              }
            }));
    verifyComponents(components);
    
    coreInjectorPackage =
        getCoreInjectorPackage(Preconditions.checkNotNull(Iterables.getFirst(components, null)));
    
    coreInjectorTree = getCoreInjectorTree(components);
    if (coreInjectorTree.isEmpty()) {
      rootComponentInfo = new ComponentInfo(
          getScopeForComponent(Iterables.getOnlyElement(components)));
    }
//    messager.printMessage(Kind.NOTE, String.format("%s components: %s", TAG, components));
//    messager.printMessage(Kind.NOTE, String.format("%s componentTree: %s", TAG, coreInjectorTree));
//    messager.printMessage(Kind.NOTE, String.format("%s scopeSizer: %s", TAG, scopeSizer));
    scopeSizer = new TreeScopeSizer(coreInjectorTree, rootComponentInfo);
//    messager.printMessage(Kind.NOTE, String.format("scopeSizer: %s", scopeSizer));
    scopedModules = HashMultimap.create();
    unscopedModules = new HashSet<>();
    getModulesInComponents(components, scopedModules, unscopedModules);
    for (ComponentInfo componentInfo : scopedModules.keySet()) {
      scopedPassedModules.putAll(componentInfo,
          Utils.getNonNullaryCtorOnes(scopedModules.get(componentInfo)));
    }
    unscopedPassedModules.addAll(Utils.getNonNullaryCtorOnes(unscopedModules));
    
    Set<TypeElement> allModules = Sets.newHashSet(scopedModules.values());
    allModules.addAll(unscopedModules);
    NewDependencyCollector dependencyCollector = new NewDependencyCollector(processingEnv);
    Collection<NewDependencyInfo> dependencyInfos =
        dependencyCollector.collect(allModules, components, allRecoverableErrors);
//    messager.printMessage(Kind.NOTE,
//        String.format("TigerDaggerGeneratorProcessor.process(). all modules: %s", allModules));
//    messager.printMessage(Kind.NOTE, String.format(
//        "TigerDaggerGeneratorProcessor.process(). all dependencyInfos: %s", dependencyInfos));

    Set<NewBindingKey> requiredKeys =
        dependencyCollector.getRequiredKeys(components, dependencyInfos);

    NewScopeCalculator newScopeCalculator =
        new NewScopeCalculator(scopeSizer, dependencyInfos, requiredKeys, processingEnv);
    allRecoverableErrors.addAll(newScopeCalculator.initialize());
    newInjectorGenerator =
        new NewInjectorGenerator(
            NewDependencyCollector.collectionToMultimap(dependencyInfos),
            newScopeCalculator,
            scopedModules,
            unscopedModules,
            getComponentToScopeMap(components),
            coreInjectorTree,
            rootComponentInfo,
            coreInjectorPackage,
            "Tiger",
            "Injector",
            processingEnv);
    newInjectorGenerator.generate();
    
    generateWrapperComponents(components);
    
    if (allRecoverableErrors.isEmpty()) {
      done = true;
    } else if (env.processingOver()) {
      for (String error : allRecoverableErrors) {
        messager.printMessage(Kind.ERROR, error);
      }
    }

    return false;
  }
  
  private String getCoreInjectorPackage(TypeElement component) {
    return Utils.getPackageString(component);
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
      Preconditions.checkNotNull(Utils.getScopeType(component),
          String.format("Unscoped component supported : %s", component));
      AnnotationMirror annotationMirror = Utils.getAnnotationMirror(component, Component.class);
      List<AnnotationValue> dependencies = null;
      AnnotationValue annotationValue = Utils.getAnnotationValue(annotationMirror, "dependencies");
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
    Map<TypeElement, ComponentInfo> componentToCoreInjectorMap =
        calculateAllMappingFromComponentsToCoreInjectors(components);
    
    for (TypeElement component : components) {
      ComponentInfo coreInjector = componentToCoreInjectorMap.get(component);
      ClassName coreInjectorClassName =
          getCoreInejectorClassName(coreInjectorPackage, coreInjector);
      String packageString = Utils.getPackageString(component);
      String generatedComponentSimpleName =
          getComponentImplementationSimpleNameFromInterface(component);
      TypeSpec.Builder componentBuilder =
          TypeSpec.classBuilder(generatedComponentSimpleName)
          .addModifiers(Modifier.PUBLIC)
          .addSuperinterface(TypeName.get(component.asType()))
          .superclass(coreInjectorClassName);
      
      // Ctor with parent component and modules as parameters.
      MethodSpec.Builder ctorBuilder =
          MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE);
      List<TypeElement> sortedComponentDependencies =
          Utils.sortByFullName(getComponentDependencies(component));
      TypeElement dependencyComponent = null;
      if (!sortedComponentDependencies.isEmpty()) {
        dependencyComponent = Iterables.getOnlyElement(sortedComponentDependencies);
        ctorBuilder.addParameter(TypeName.get(dependencyComponent.asType()),
            Utils.getSourceCodeName(dependencyComponent));
      }
      Set<TypeElement> allComponentModules = getAllModulesOfComponentRecursively(component);
      List<TypeElement> sortedComponentPassedModules =
          Utils.sortByFullName(Utils.getNonNullaryCtorOnes(allComponentModules));
      for (TypeElement typeElement : sortedComponentPassedModules) {
        ctorBuilder.addParameter(TypeName.get(typeElement.asType()),
            Utils.getSourceCodeName(typeElement));
      }
      Set<TypeElement> coreInjectorPassedModules = new HashSet<>();
      coreInjectorPassedModules.addAll(scopedPassedModules.get(coreInjector));
      coreInjectorPassedModules.addAll(unscopedPassedModules);
      List<TypeElement> sortedCoreInjectorPassedModules =
          Utils.sortByFullName(coreInjectorPassedModules);
      StringBuilder stringBuilder = new StringBuilder("super(");
      if (dependencyComponent != null) {
        String generatedDependencyComponentSimpleName =
            getComponentImplementationSimpleNameFromInterface(dependencyComponent);
        // Cast the interface to implement which is a subclass of core injector therefore will be
        // accept by compiler.
        stringBuilder.append("($T) ").append(Utils.getSourceCodeName(dependencyComponent));
        if (!sortedCoreInjectorPassedModules.isEmpty()) {
          stringBuilder.append(", ");
        }
      }
      if (!sortedCoreInjectorPassedModules.isEmpty()) {
        for (TypeElement typeElement : sortedCoreInjectorPassedModules) {
          if (sortedComponentPassedModules.contains(typeElement)) {
            stringBuilder.append(Utils.getSourceCodeName(typeElement));
          } else {
            stringBuilder.append("null");
          }
          stringBuilder.append(", ");
        }
        stringBuilder.delete(stringBuilder.length() - 2, stringBuilder.length());
      }
      stringBuilder.append(");");
      if (dependencyComponent != null) {
        ctorBuilder.addCode(stringBuilder.toString(), ClassName.get(packageString,
            getComponentImplementationSimpleNameFromInterface(dependencyComponent)));
      } else {
        ctorBuilder.addCode(stringBuilder.toString());
      }
      componentBuilder.addMethod(ctorBuilder.build());
      
      ClassName generatedComponentClassName = ClassName.get(packageString,
          generatedComponentSimpleName);
      generateComponentBuilder(generatedComponentClassName, dependencyComponent,
          sortedComponentPassedModules, componentBuilder, coreInjector);

      ClassName builderClassName =
          ClassName.get(packageString, generatedComponentSimpleName, "Builder");
      componentBuilder.addMethod(
          MethodSpec.methodBuilder("builder")
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
              .returns(builderClassName)
              .addCode("return new $T();", builderClassName).build());
      
      JavaFile javaFile =
          JavaFile.builder(packageString, componentBuilder.build()).build();
      try {
        messager.printMessage(Kind.NOTE,
            String.format("%s: writing java file: %s", TAG, javaFile.toJavaFileObject().getName()));
        javaFile.writeTo(processingEnv.getFiler());
      } catch (IOException e) {
        Throwables.propagate(e);
      }
    }
  }
  
  private Set<TypeElement> getAllModulesOfComponentRecursively(TypeElement component) {
    Set<TypeElement> result = new HashSet<>();
    AnnotationMirror componentAnnotationMirror =
        Utils.getAnnotationMirror(component, Component.class);
    for (AnnotationValue annotationValue : (List<AnnotationValue>) Utils
        .getAnnotationValue(componentAnnotationMirror, "modules").getValue()) {
      result.add((TypeElement) ((DeclaredType) annotationValue.getValue()).asElement());
    }
    result = Utils.findAllModulesRecursively(result);
    return result;
  }

  private void generateComponentBuilder(ClassName componentClassName,
      @Nullable TypeElement dependencyComponent, List<TypeElement> sortedPassedModules,
      TypeSpec.Builder componentBuilder, ComponentInfo coreInjector) {
    TypeSpec.Builder builderBuilder =
        TypeSpec.classBuilder("Builder").addModifiers(Modifier.PUBLIC, Modifier.STATIC);
    
    // set parent inject methods.
    ClassName dependencyClassName = null;
    if (dependencyComponent != null) {
      dependencyClassName = (ClassName) ClassName.get(dependencyComponent.asType());
      Utils.addSetMethod(componentClassName, builderBuilder, dependencyClassName);
    }
    
    /**
     * Set module methods.
     */
    for (TypeElement m : sortedPassedModules) {
      Utils.addSetMethod(componentClassName, builderBuilder, (ClassName) ClassName.get(m.asType()));
    }
    
    // build() method.
    MethodSpec.Builder buildMethodBuilder =
        MethodSpec.methodBuilder("build").addModifiers(Modifier.PUBLIC).returns(componentClassName);
    StringBuilder returnCodeBuilder = new StringBuilder("return new $T(");
    if ( dependencyClassName != null) {
      returnCodeBuilder.append(Utils.getSourceCodeName(dependencyComponent));
      if (!sortedPassedModules.isEmpty()) {
        returnCodeBuilder.append(", ");
      }
    }
    if (!sortedPassedModules.isEmpty()) {
      for (TypeElement module : sortedPassedModules) {
        String moduleFiledName = Utils.getSourceCodeName(module);
        returnCodeBuilder.append(moduleFiledName).append(", ");
      }
      returnCodeBuilder.delete(returnCodeBuilder.length() - 2, returnCodeBuilder.length());
    }
    returnCodeBuilder.append(");");
    buildMethodBuilder.addCode(returnCodeBuilder.toString(), componentClassName);
    builderBuilder.addMethod(buildMethodBuilder.build());
    
    componentBuilder.addType(builderBuilder.build());
  }


  private ClassName getCoreInejectorClassName(String coreInjectorPackage, ComponentInfo componentInfo) {
    return ClassName.get(coreInjectorPackage, newInjectorGenerator.getTopLevelInjectorName(componentInfo));
  }

  private String getComponentImplementationSimpleNameFromInterface(TypeElement component) {
    String packageString = Utils.getPackageString(component);
    String classNameString =
        component.getQualifiedName().toString().substring(packageString.length());
    if (classNameString.startsWith(".")) {
      classNameString = classNameString.substring(1);
    }
    return "Dagger" + classNameString.replace(".", "_");
  }

  /**

  /**
   * The injector tree with on injector for each scope. The map is from child to parent.
   */
  private Map<ComponentInfo, ComponentInfo> getCoreInjectorTree(Set<TypeElement> components) {
    Map<ComponentInfo, ComponentInfo> result = new HashMap<>();

    Map<TypeElement, ComponentInfo> componentScopeMap =
        calculateAllMappingFromComponentsToCoreInjectors(components);

    for (TypeElement component : componentScopeMap.keySet()) {
      ComponentInfo componentInfo = componentScopeMap.get(component);
      for (TypeElement parentComponent : getComponentDependencies(component)) {
        ComponentInfo parentComponentInfo = componentScopeMap.get(parentComponent);
        if (parentComponentInfo == null || parentComponentInfo.equals(componentInfo)) {
          continue;
        }
        result.put(componentInfo, parentComponentInfo);
      }
    }

    return result;
  }

  /**
   * Returns dependency components of the give component, empty set if none.
   */
  private Set<TypeElement> getComponentDependencies(TypeElement component) {
    Set<TypeElement> result = new HashSet<>();
    AnnotationMirror componentAnnotationMirror =
        Preconditions.checkNotNull(Utils.getAnnotationMirror(component, Component.class),
            String.format("@Component not found for %s", component));
    
    AnnotationValue dependenciesAnnotationValue =
        Utils.getAnnotationValue(componentAnnotationMirror, "dependencies");
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
  
  /**
   * Mapps the give components according to their calculated scope. Unscoped ones belongs to root
   * scope.
   */
  private SetMultimap<ComponentInfo, TypeElement> getComponentToScopeMap(
      Set<TypeElement> components) {
    SetMultimap<ComponentInfo, TypeElement> result = HashMultimap.create();
    Map<TypeElement, ComponentInfo> mapping =
        calculateAllMappingFromComponentsToCoreInjectors(components);
    for (Map.Entry<TypeElement, ComponentInfo> entry : mapping.entrySet()) {
      result.put(entry.getValue(), entry.getKey());
    }
    
    if (!coreInjectorTree.isEmpty()) {
      messager.printMessage(Kind.NOTE, "getMappedMembersInjectors. coreInjectorTree: "
          + coreInjectorTree + " mapping: " + mapping);
      for (TypeElement typeElement : Sets.difference(components, mapping.keySet())) {
        result.put(rootComponentInfo, typeElement);
      }
    }
    return result;
  }

  /**
   * Return the explicit scopes and implicit scopes of give components. Those with neither explicit
   * or implicit scope are excluded.
   */
  private Map<TypeElement, ComponentInfo> calculateAllMappingFromComponentsToCoreInjectors(
      Set<TypeElement> components) {
    Map<TypeElement, ComponentInfo> componentScopeMap = new HashMap<>();
    for (TypeElement component : components) {
      TypeElement scope = getScopeForComponent(component);
      if (scope != null) {
        componentScopeMap.put(component, new ComponentInfo(scope));
      }
    }
    return componentScopeMap;
  }

  /**
   * Returns scope for the give dagger Component, null is unscoped. The result is either
   * explicitly specified or implicitly inherited from one of the ancestors.
   */
  @Nullable
  private TypeElement getScopeForComponent(TypeElement component) {
    DeclaredType scope = Utils.getScopeType(component);
    if (scope != null) {
      return (TypeElement) scope.asElement();
    }
    AnnotationMirror componentAnnotationMirror =
        Utils.getAnnotationMirror(component, Component.class);
    List<AnnotationValue> dependencies = (List<AnnotationValue>) Utils
        .getAnnotationValue(componentAnnotationMirror, "dependencies");
    if (dependencies == null) {
      return null;
    }
    Set<TypeElement> parentScopes = new HashSet<>();
    for (AnnotationValue dependency : dependencies) {
      DeclaredType dependencyClass = (DeclaredType) dependency.getValue();
      TypeElement parentScope = getScopeForComponent((TypeElement) dependencyClass.asElement());
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
      SetMultimap<ComponentInfo, TypeElement> scopeModules, Set<TypeElement> unscopedModules) {
    Set<TypeElement> modules = new HashSet<>();
    for (Element component : components) {
      AnnotationMirror componentAnnotationMirror =
          Utils.getAnnotationMirror(component, Component.class);
      for (AnnotationValue annotationValue : (List<AnnotationValue>) Utils
          .getAnnotationValue(componentAnnotationMirror, "modules").getValue()) {
        modules.add((TypeElement) ((DeclaredType) annotationValue.getValue()).asElement());
      }
    }
    modules = Utils.findAllModulesRecursively(modules);
    for (TypeElement module : modules) {
      TypeElement scopeType = Utils.getModuleScope((DeclaredType) module.asType());
      if (scopeType != null) {
        scopeModules.put(new ComponentInfo(scopeType), module);
      } else {
        unscopedModules.add(module);
      }
    }
  }

  /**
   * Creates a map from child to parent from the give {@link TypeElement}, which is annotated with
   * {@link ScopeDependency} and {@link ScopeComponentNames}.
   */
  private Map<ComponentInfo, ComponentInfo> getComponentTree(TypeElement typeElement) {

    Map<ComponentInfo, ComponentInfo> result = new HashMap<>();
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

  private TypeElement getTypeElementFrom(AnnotationValue annotationValue) {
    return (TypeElement) ((DeclaredType) annotationValue.getValue()).asElement();
  }
  

  private Component getComponentForInjectionScope(DeclaredType scopeType) {
//    Element scopeTypeElement = scopeType.asElement();
//    for (Component component : Component.values()) {
//      if (elementUtils
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
    return Sets.newHashSet(Component.class.getCanonicalName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}
