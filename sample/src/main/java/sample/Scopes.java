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

import tiger.GenerationTriggerAnnotation;
import tiger.PackageForGenerated;
import tiger.ScopeDependency;
import tiger.ScopedComponentNames;
import javax.inject.Singleton;

/**
 * Injection information for the sample.
 */
@GenerationTriggerAnnotation
@PackageForGenerated("sample")
public class Scopes {

  @ScopedComponentNames(scope = Singleton.class, name = "Application" )
  public static class ForApplication {}

  @ScopedComponentNames(scope = sample.ActivityScoped.class, name = "Activity" )
  @ScopeDependency(scope = sample.ActivityScoped.class, parent = Singleton.class)
  public static class ForActivityScoped {}
}
