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
package tiger;

/**
 * Names shared between {@link ComponentGeneratorProcessor} and
 * {@link DependencyInformationCollectorProcessor}.
 */
public class SharedNames {
  public static final String DEPENDENCY_INFORMATION_PACKAGE_NAME =
      "com.google.injection.dependency_information_package";
  public static final String DEPENDENCY_INFORMATION_FIELD_NAME_MODULES =
      "dependencyInformationFieldNameModules";
  public static final String DEPENDENCY_INFORMATION_FIELD_NAME_MEMBERS_INJECTORS =
      "dependencyInformationFieldMembersInjectors";
  public static final String DEPENDENCY_INFORMATION_FIELD_NAME_CTOR_INJECTED_CLASSES =
      "dependencyInformationFieldCtorInjectedClasses";
  public static final String DEPENDENCY_INFORMATION_FIELD_NAME_SCOPE_DEPENDENCIES =
      "dependencyInformationFieldScopeDependencies";
  public static final String DEPENDENCY_INFORMATION_FIELD_NAME_SCOPED_COMPONENT_NAMES =
      "dependencyInformationFieldScopedComponentNames";
  public static final String DEPENDENCY_INFORMATION_FIELD_NAME_PACKAGE_FOR_GENERATED =
      "dependencyInformationFieldPackageForGenerated";
}
