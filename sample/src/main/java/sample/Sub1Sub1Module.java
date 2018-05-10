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

@Module
public class Sub1Sub1Module {
  Sub1Sub1 sub1Sub1;

  Sub1Sub1Module(Sub1Sub1 sub1Sub1) {
    this.sub1Sub1 = sub1Sub1;
  }

  @Provides
  @FragmentScoped
  Sub1Sub1 getSub1Sub1() {
    return sub1Sub1;
  }

  @Provides
  @FragmentScoped
  Sub1Sub1Foo provideSub1Sub1Foo() {
    return new Sub1Sub1Foo();
  }
}
