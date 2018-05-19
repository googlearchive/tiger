package tiger;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/** Created by freemanliu on 5/3/18. */
public class ExtraDependenciesOnParentCalculator {
  private static ExtraDependenciesOnParentCalculator instance;

  private final ProcessingEnvironment processingEnvironment;
  private final Messager messager;
  private final Types types;
  private final Utils utils;
  private Logger logger;

  private SetMultimap<TypeElement, TypeElement> eitherComponentToChildrenMap =
      HashMultimap.create();
  private Map<TypeElement, Set<BindingKey>> eitherComponentToKeyMap = new HashMap<>();
  private DependencyCollector dependencyCollector;
  private Map<TypeElement, TypeElement> eitherComponentToParentMap;

  public static ExtraDependenciesOnParentCalculator getInstance(
      Map<TypeElement, TypeElement> eitherComponentToParentMap,
      ProcessingEnvironment processingEnvironment,
      Utils utils) {
    if (instance == null) {
      instance =
          new ExtraDependenciesOnParentCalculator(
              eitherComponentToParentMap, processingEnvironment, utils);
      instance.initialize(eitherComponentToParentMap);
    } else {
    }

    return instance;
  }

  private void initialize(Map<TypeElement, TypeElement> eitherComponentToParentMap) {

    this.eitherComponentToParentMap = eitherComponentToParentMap;
    for (Map.Entry<TypeElement, TypeElement> i : eitherComponentToParentMap.entrySet()) {
      eitherComponentToChildrenMap.put(i.getValue(), i.getKey());
    }
    dependencyCollector = DependencyCollector.getInstance(processingEnvironment, utils);
  }

  private ExtraDependenciesOnParentCalculator(
      Map<TypeElement, TypeElement> eitherComponentToParentMap,
      ProcessingEnvironment processingEnvironment, Utils utils) {
    Preconditions.checkNotNull(eitherComponentToParentMap);
    Preconditions.checkArgument(
        this.eitherComponentToParentMap == null
            || this.eitherComponentToParentMap.equals(eitherComponentToParentMap),
        "wrong map: %s", eitherComponentToParentMap);
    this.eitherComponentToParentMap = eitherComponentToParentMap;
    this.processingEnvironment = processingEnvironment;
    this.messager = processingEnvironment.getMessager();
    this.types = processingEnvironment.getTypeUtils();
    this.utils = utils;
    this.logger = new Logger(processingEnvironment.getMessager(), Kind.WARNING);
  }

  /** TODO: remove parent argument, also in {@link DependencyCollector}. */
  public Set<BindingKey> calculate(
      TypeElement eitherComponent, @Nullable TypeElement parentEitherComponent) {
    if (eitherComponentToKeyMap.containsKey(eitherComponent)) {
      return eitherComponentToKeyMap.get(eitherComponent);
    }

    Set<BindingKey> unresolved = new HashSet<>();
    SetMultimap<BindingKey, DependencyInfo> dependencies =
        DependencyCollector.collectionToMultimap(
            dependencyCollector.collectForOne(eitherComponent, parentEitherComponent, unresolved));
    logger.n("(sub)component: %s unresolved collected: %s", eitherComponent, unresolved);

    Set<BindingKey> fromChildrenAndPackages = new HashSet<>();
    for (TypeElement child : eitherComponentToChildrenMap.get(eitherComponent)) {
      fromChildrenAndPackages.addAll(calculate(child, eitherComponent));
    }

    // for (TypeName i : utils.collectPackagedHubInterfaces(eitherComponent, dependencies)) {
    //   TypeElement typeElement = utils.getTypeElementForClassName((ClassName) i);
    //   if (typeElement == null) {
    //     logger.e("package not found: %s", i);
    //     continue;
    //   }
    //   logKey(eitherComponent, "package: %s", i);
    //   for (Element e : typeElement.getEnclosedElements()) {
    //     ExecutableElement method = (ExecutableElement) e;
    //     // logger.n("component: %s, method: %s", eitherComponent, method);
    //     BindingKey key;
    //     if (utils.isProvisionMethodInInjector(method)) {
    //       key = utils.getKeyProvidedByMethod(method);
    //       logKey(eitherComponent, "key:%s, method: %s", key, method);
    //       if ((key.getQualifier() == null
    //               || utils
    //                   .getTypeElement(key.getQualifier().type)
    //                   .getModifiers()
    //                   .contains(Modifier.PUBLIC))
    //           && utils.isPublicRecurively(key.getTypeName())) {
    //         fromChildrenAndPackages.add(key);
    //       }
    //     } else {
    //       Preconditions.checkState(utils.isInjectionMethod(method));
    //       // key =
    //       //     utils.getKeyForOnlyParameterOfMethod(
    //       //         types, (DeclaredType) utils.getTypeFromTypeName(i), method);
    //     }
    //   }
    // }

    for (BindingKey k : fromChildrenAndPackages) {
      Set<DependencyInfo> dependencyInfos = utils.getDependencyInfo(dependencies, k);
      boolean resolvable = dependencyInfos != null;
      logKey(eitherComponent, "key: %s, resovable %s, di: %s", k, resolvable, dependencyInfos);
      if (!resolvable) {
        unresolved.add(k);
      }
    }
    eitherComponentToKeyMap.put(eitherComponent, unresolved);
    logger.w(
        "(sub)component: %s\ndependencies: %s\nunresolved: %s",
        eitherComponent, dependencies, unresolved);
    return unresolved;
  }

  private void logKey(TypeElement eitherComponent, String fmt, Object... args) {
    // if (eitherComponent
    //     .getQualifiedName()
    //     .toString()
    //     .contains("AddAPlaceModule_BindAddAPlaceFragmentInjector.AddAPlaceFragmentSubcomponent"))
    // {
    if (args[0] instanceof BindingKey) {
        AnnotationSpec qualifier = ((BindingKey) args[0]).getQualifier();
      if (qualifier != null && qualifier.toString().contains("CriticalRequestWhitelist")) {
        // logger.e(fmt, args);
        }
    }
  }
}
