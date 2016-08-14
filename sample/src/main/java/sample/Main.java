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

public class Main {

  public static void main(String[] args) {
    DaggerApplicationComponent applicationComponent = new DaggerApplicationComponent.Builder().build();
    PseudoApplication application = new PseudoApplication();
    PseudoActivity activity = new PseudoActivity();
    activity.onCreate(applicationComponent);
    PseudoActivity activity2 = new PseudoActivity();
    activity2.onCreate(applicationComponent);

    applicationComponent.injectPseudoApplication(application);

    System.out.println("application: " + application);
    System.out.println("activity: " + activity);
    System.out.println("activity2: " + activity2);
  }
}
