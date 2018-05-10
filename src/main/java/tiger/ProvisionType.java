package tiger;

import dagger.Provides.Type;

/**
 * Provision type mimic original dagger {@link dagger.Provides.Type}. But MAP was removed from it.
 * Sigh~
 */
public enum ProvisionType {
  UNIQUE,
  SET,
  SET_VALUES,
  MAP,
  ;

  public static ProvisionType fromDaggerType(Type type) {
    switch (type) {
      case UNIQUE:
        return UNIQUE;
      case SET:
        return SET;
      case SET_VALUES:
        return SET_VALUES;
      default:
        throw new RuntimeException("Unknown provision type: " + type);
    }
  }
}
