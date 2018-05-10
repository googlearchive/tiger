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

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import dagger.Lazy;
import dagger.MembersInjector;
import javax.inject.Inject;
import sample.child.Shboom;

public class PseudoActivity {

  @Inject @BarAnnotation Bar bar;

  @Inject @BarAnnotation Bar bar2;

  @Inject Baz baz;

  @Inject Shboom shboom;

  @Inject Alien alien;

  @Inject FooByStatic fooByStatic;

  @Inject Optional<OptYes> optYes;

  @Inject Optional<Lazy<OptYes>> lazyOptYes;

  @Inject Optional<OptNo> optNo;

  @Inject MembersInjector<Buggie> buggieInjector;

  Buggie buggie = new Buggie();

  void onCreate(DaggerApplicationComponent applicationComponent) {
    DaggerActivityComponent activityComponent =
        (DaggerActivityComponent)
            DaggerActivityComponent.builder()
                .s1(applicationComponent)
                .setAlienSource(new AlienSource("Martian"))
                .b();
    activityComponent.injectPseudoActivity(this);
    buggieInjector.injectMembers(buggie);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper("PseudoActivity[bar: ")
        .add("bar", bar)
        .add(" bar2: ", bar2)
        .add(", baz: ", baz)
        .add(" shboom: ", shboom)
        .add(" FooByStatic: ", fooByStatic)
        .add(" alien: ", alien)
        .add(" optYes: ", optYes.isPresent())
        .add(" lazyoptYes: ", lazyOptYes.isPresent() + "/" + lazyOptYes.get().get())
        .add(" optNo: ", optNo.isPresent())
        .add("buggie", buggie)
        .toString();
  }
}
