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

import sample.ApplicationComponent.AppSub1Component;
import sample.ApplicationComponent.AppSub1Component2;

public class Main {

  public static void main(String[] args) {

    // inject app
    PseudoApplication application = new PseudoApplication();
    DaggerApplicationComponent applicationComponent =
        (DaggerApplicationComponent)
            DaggerApplicationComponent.builder().s1(new ApplicationModule(null)).create(application);
    applicationComponent.injectPseudoApplication(application);
    System.out.println("application: " + application);

    // inject activities
    PseudoActivity activity = new PseudoActivity();
    activity.onCreate(applicationComponent);
    PseudoActivity activity2 = new PseudoActivity();
    activity2.onCreate(applicationComponent);

    System.out.println("activity: " + activity);
    System.out.println("activity2: " + activity2);


    // inject field of type subcomponent added by module.
    {
      Foo foo = applicationComponent.provideFoo();
      Sub1 sub1 = new Sub1();

      AppSub1Component appSub1Component = foo.b
          .s1(new Sub1Module(sub1))
          .planet(new Planet() {
            @Override
            public int hashCode() {
              return super.hashCode();
            }
          })
          .build();
      System.out.println("Subcomponent by module: inject subcomponent by field");
      System.out.println("before injection, sub1: " + sub1 + "sub1Foo: " + sub1.sub1Foo);
      appSub1Component.injectSub1(sub1);
      System.out.println("after injection, sub1: " + sub1 + "sub1Foo: " + sub1.sub1Foo);
    }

    // inject field of type subcomponent added by module.
    {
      Foo2 foo2 = applicationComponent.provideFoo2();
      Sub1 sub1 = new Sub1();

      AppSub1Component appSub1Component = foo2.b
          .s1(new Sub1Module(sub1))
           .planet(new Mars())
          .build();
      System.out.println("Subcomponent by module: inject subcomponent builder by ctor");
      System.out.println("before injection, sub1: " + sub1 + "sub1Foo: " + sub1.sub1Foo);
      appSub1Component.injectSub1(sub1);
      System.out.println("after injection, sub1: " + sub1 + "sub1Foo: " + sub1.sub1Foo);
    }

    // inject field of type subcomponent added by module.
    {
      Foo3 foo3 = applicationComponent.provideFoo3();
      Sub1 sub1 = new Sub1();

      AppSub1Component appSub1Component = foo3.b
          .s1(new Sub1Module(sub1))
           .planet(new Mercury())
          .build();
      System.out.println("Subcomponent by module: inject subcomponent builder by module getter");
      System.out.println("before injection, sub1: " + sub1 + "sub1Foo: " + sub1.sub1Foo);
      appSub1Component.injectSub1(sub1);
      System.out.println("after injection, sub1: " + sub1 + "sub1Foo: " + sub1.sub1Foo);
    }

    // Inject @Component.Build by field injection.
    // {
    //   Foo foo = applicationComponent.provideFoo();
    //   ActivityComponent activityComponent = foo.builder.s1(applicationComponent).b();
    //   PseudoActivity pseudoActivity = new PseudoActivity();
    //   System.out.println("Inject @Component.Build by field injection");
    //   System.out.println("before injection, psuedoActivity: " + pseudoActivity);
    //   activityComponent.injectPseudoActivity(pseudoActivity);
    //   System.out.println("after injection, psuedoActivity: " + pseudoActivity);
    //
    // }

    // inject subcomponent by provision method
    // {
    //   Sub1 sub1 = new Sub1();
    //
    //   AppSub1Component2 appSub1Component2 =
    //       applicationComponent.getAppSub1Component2(new Sub1Module(sub1));
    //   System.out.println("Subcomponent by factory method:");
    //   System.out.println("injection sub1Foo: " + appSub1Component2.provideSub1Foo());
    // }

    // inject appsub1sub1
    // Sub1Sub1 sub1Sub1 = new Sub1Sub1();
    // AppSub1Sub1Component appSub1Sub1Component =
    //     sub1.appSub1Sub1ComponentBuilder.setSub1Sub1Module(new Sub1Sub1Module(sub1Sub1)).build();
    // System.out.println("Subcomponent by module:");
    // System.out.println("before injection, sub1sub1: " + sub1Sub1 + "sub1Foo: " + sub1Sub1.sub1sub1Foo);
    // appSub1Sub1Component.injectSub1Sub1(sub1Sub1);
    // System.out.println("after injection, sub1sub1: " + sub1Sub1 + "sub1Foo: " + sub1Sub1.sub1sub1Foo);

  }
}
