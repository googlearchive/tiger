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
package sample;

import com.google.android.apps.docs.tools.dagger.componentfactory.MembersInjector;
import dagger.BindsInstance;
import dagger.Component;

import dagger.Provides;
import dagger.Subcomponent;
import dagger.Subcomponent.Builder;
import dagger.android.AndroidInjector;
import java.util.Map;
import java.util.Set;

import javax.inject.Singleton;

@Singleton
@Component(modules = {ApplicationModule.class, AbstractApplicationModule.class})
@MembersInjector(scope = Singleton.class)
public interface ApplicationComponent extends AndroidInjector<PseudoApplication> {
  void injectPseudoApplication(PseudoApplication application);

  Foo provideFoo();
  FooByStatic provideFooByStatic();
  Foo2 provideFoo2();
  Foo3 provideFoo3();

  Set<Kablam> provideKabalmSet();

  //Map<String, Planet> providePlanets();

  // ActivityComponent.Builder getActivityComponentBuilder();

  AppSub1Component.B getAppSub1ComponentBuiler();

  @Component.Builder
  abstract class MyBuilder extends AndroidInjector.Builder<PseudoApplication> {
    abstract MyBuilder s1(ApplicationModule v);
  }

  @ActivityScoped
  @Subcomponent(modules = Sub1Module.class)
  interface AppSub1Component{
    Sub1Foo provideSub1Foo();
    void injectSub1(Sub1 sub1);

    @Subcomponent.Builder
    interface B{
      @BindsInstance B planet(Planet p);
      B s1(Sub1Module v);
      AppSub1Component build();
    }
  }

  @ActivityScoped
  @Subcomponent(modules = Sub1Module.class)
  interface AppSub1Component2{
    Sub1Foo provideSub1Foo();
  }

  AppSub1Component2 getAppSub1Component2(Sub1Module sub1Module);
}
