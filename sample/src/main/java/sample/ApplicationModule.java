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

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;

import java.util.HashSet;
import java.util.Set;
import javax.inject.Singleton;
import sample.ActivityComponent.Builder;
import sample.ApplicationComponent.AppSub1Component;
import sample.ApplicationComponent.AppSub1Component.B;

@Module(subcomponents = AppSub1Component.class)
public class ApplicationModule {

  private final Main app;

  public ApplicationModule(Main app) {
    this.app = app;
  }

  @Provides
  Foo3 provideFoo3(AppSub1Component.B b) {
    return new Foo3(b);
  }

  @Provides
  static FooByStatic provideFooByStatic(B b) {
    return new FooByStatic(b);
  }

  @Provides
  @IntoSet
  Kablam provideKablamSet() {
    return new Kablam();
  }

  @Provides
  @ElementsIntoSet
  Set<String> provideStrings() { return new HashSet<>();}

  @Provides
  @IntoMap
  @PlanetKey("mars")
  Planet provideMars() {
    return new Mars();
  }

  @Provides
  @IntoMap
  @PlanetKey("mercury")
  Planet provideMercury() {
    return new Mercury();
  }
}
