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
import dagger.multibindings.IntoSet;
import javax.inject.Named;
import sample.ApplicationComponent.AppSub1Component;
import sample.ApplicationComponent.AppSub1Component.B;

@Module
public abstract class AbstractApplicationModule {

  @Binds
  abstract Tank getTank(Panther v);

  @Binds
  @IntoSet
  @Named("Russia")
  abstract Tank getT34(T34 v);

  @Binds
  @IntoSet
  @Named("Russia")
  abstract Tank getT62(T62 v);
}
