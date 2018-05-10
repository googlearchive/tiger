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

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import javax.inject.Singleton;

@Module(subcomponents = AppSub1Sub1Component.class)
public class Sub1Module {
  Sub1 sub1;

  Sub1Module(Sub1 sub1) {
    this.sub1 = sub1;
  }

  @Provides
  @ActivityScoped
  Sub1 getSub1() {
    return sub1;
  }

  @Provides
  @ActivityScoped
  Sub1Foo provideSub1Foo() {
    return new Sub1Foo();
  }
}
