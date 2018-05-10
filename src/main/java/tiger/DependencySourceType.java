package tiger;

/**
 * Sources of dependencies.
 */
public enum DependencySourceType {
  CTOR_INJECTED_CLASS(90), /** from inside */
  DAGGER_MEMBERS_INJECTOR(50),
  MODULE(100),
  COMPONENT_DEPENDENCIES_METHOD(60),
  COMPONENT_DEPENDENCIES_ITSELF(60),
  EITHER_COMPONENT(70),
  EITHER_COMPONENT_BUILDER(70),
  BINDS_INTANCE(80),
  NONE(-1); // TODO: remove this

  public int getPriority() {
    return priority;
  }

  /**
   * The bigger the higher.
   * TODO: remove this.
   */
  private final int priority;

  DependencySourceType(int priority) {
    this.priority = priority;
  }
}
