package sample;

import com.google.common.base.MoreObjects;
import java.util.Objects;
import javax.inject.Inject;
import sample.ApplicationComponent.AppSub1Component;

public class Sub1 {
  @Inject
  Sub1Foo sub1Foo;


  @Inject
  Planet planet;

      @Override
  public String toString () {
        return MoreObjects.toStringHelper(this)
            .add("sub1Foo", sub1Foo)
            .add("planet", planet)
            .toString();
      }
  // @Inject
  // AppSub1Sub1Component.Builder appSub1Sub1ComponentBuilder;
}
