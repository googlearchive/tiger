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

import javax.inject.Inject;
import sample.child.Shboom;

public class PseudoActivity {
  @Inject
  Foo foo;

  @Inject
  @BarAnnotation
  Bar bar;

  @Inject
  @BarAnnotation
  Bar bar2;

  @Inject
  Baz baz;
  
  @Inject
  Shboom shboom;

  void onCreate(DaggerApplicationComponent applicationComponent) {
    DaggerActivityComponent activityComponent =
        new DaggerActivityComponent.Builder().daggerApplicationComponent(applicationComponent).build();
    activityComponent.injectPseudoActivity(this);
  }

  @Override
  public String toString() {
    return "PseudoActivity[foo: " + foo + ", bar: " + bar + " bar2: " + bar2 + ", baz: " + baz
        + " shboom: " + shboom + "]";
  }
}
