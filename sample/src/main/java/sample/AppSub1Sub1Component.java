package sample;

import dagger.Subcomponent;

/** Created by freemanliu on 3/15/18. */
@FragmentScoped
@Subcomponent(modules = Sub1Sub1Module.class)
public interface AppSub1Sub1Component {
  Sub1Sub1Foo provideSub1Sub1Foo();

  void injectSub1Sub1(Sub1Sub1 sub1);

  @Subcomponent.Builder
  interface Builder{
    Builder setSub1Sub1Module(Sub1Sub1Module sub1Sub1Module);
    AppSub1Sub1Component build();
  }
}
