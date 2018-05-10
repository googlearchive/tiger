package tiger;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

/**
 * Condenses scope alias to the single core scope.
 */
public class ScopeAliasCondenser {

  private Map<TypeElement, TypeElement> aliasToCoreMap = new HashMap<>();

  /**
   * Returns mapping from all scope aliases to the core alias, which is selected from all the
   * aliases in some way.
   */
  public ScopeAliasCondenser(Messager messager, Set<TypeElement> components) {
    for (TypeElement c : components) {
      Set<TypeElement> all = Utils.getScopeTypeElements(c);
      TypeElement first = null;
      for (TypeElement s : Utils.getScopeTypeElements(c)) {
        if (aliasToCoreMap.containsKey(s)) {
          first = aliasToCoreMap.get(s);
              break;
        }
      }
      if (first == null) {
        first = Iterables.getFirst(all, null);
      }

      for (TypeElement s : Utils.getScopeTypeElements(c)) {
        aliasToCoreMap.put(s, first);
      }
    }

    normalize();
    messager.printMessage(Kind.NOTE, "collectScopeAlias: map: " + aliasToCoreMap);
  }

  /**
   * Change the core to the shortest one if it is not.
   */
  private void normalize() {
    Map<TypeElement, TypeElement> newMap = new HashMap<>();
    SetMultimap<TypeElement, TypeElement> map = HashMultimap.create();
    aliasToCoreMap.forEach((k,v) -> {map.put(v,k);});
    for (TypeElement core : map.keySet()) {
      TypeElement newCore = findShortest(map.get(core));
      for (TypeElement alias : map.get(core)) {
        newMap.put(alias, newCore);
      }
    }
    aliasToCoreMap = newMap;
  }

  /**
   * Returns the one with shortest qualified name.
   */
  private TypeElement findShortest(Set<TypeElement> typeElements) {
    TypeElement result = Preconditions.checkNotNull(Iterables.getFirst(typeElements, null));
    for (TypeElement v : typeElements) {
      if (v.getQualifiedName().toString().length()
          < result.getQualifiedName().toString().length()) {
        result = v;
      }
    }
    return result;
  }

  /**
   * Returns the core scope for the give scope alias. Could be itself if it is the core.
   */
  public TypeElement getCoreScopeForAlias(TypeElement scope) {
    return Preconditions.checkNotNull(aliasToCoreMap.get(scope), "scope: " + scope);
  }
}
