#!/bin/bash -eu
#
# Copyright 2016 Google Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
################################################################################
#
#-*- mode: python; -*-
# Copyright 2015 Google Inc. All Rights Reserved.
#
# Description:
#   Tools that verify the dependencies and scopes of a given jar.

package(default_visibility = ["//visibility:legacy_public"])

COMMON_DEPS = [
    "//java/com/google/android/apps/docs/tools/dagger/componentfactory",
    "//java/com/google/android/libraries/docs/inject/annotations",
    "//java/com/google/common/base",
    "//java/com/google/common/io",
    "//java/com/google/common/reflect",
    "//third_party/java/android_libs/guava_jdk5:annotations",
    "//third_party/java/android_libs/guava_jdk5:base",
    "//third_party/java/android_libs/guava_jdk5:collect",
    "//third_party/java/android_libs/guava_jdk5:hash",
    "//java/com/google/android/apps/docs/tools/freemarker:freemarker_custom_file_template_loader_lib",
    "//third_party/java/auto:auto_service",
    "//third_party/java/auto:common",
    "//third_party/java/gson",
    "//third_party/java/dagger:dagger_lib",
    "//third_party/java/javapoet",
    "//third_party/java/jsr330_inject",
    "//third_party/java/freemarker",
    "//third_party/java/jsr305_annotations",
]

TIGER_DAGGER_DEPS = [
    #    "//java/com/google/android/apps/docs/tools/dagger/codegeneration:binding_checker_lib",
    "//java/com/google/android/apps/docs/tools/dagger/codegeneration:utilities",
    #    "//java/com/google/android/apps/docs/tools/dagger/componentfactory",
    "//java/com/google/android/apps/docs/tools/dagger/componentfactory:annotations",
    "//java/com/google/android/libraries/docs/inject/annotations",
    "//java/com/google/common/base",
    "//java/com/google/common/collect",
    "//java/com/google/common/io",
    "//java/com/google/common/reflect",
    #    "//third_party/java/android_libs/guava_jdk5:annotations",
    #    "//third_party/java/android_libs/guava_jdk5:base",
    #    "//third_party/java/android_libs/guava_jdk5:collect",
    #    "//third_party/java/android_libs/guava_jdk5:hash",
    "//java/com/google/android/apps/docs/tools/freemarker:freemarker_custom_file_template_loader_lib",
    "//third_party/java/auto:auto_service",
    "//third_party/java/auto:common",
    "//third_party/java/gson",
    "//third_party/java/dagger:dagger_lib",
    "//third_party/java/javapoet",
    "//third_party/java/jsr330_inject",
    "//third_party/java/freemarker",
    "//third_party/java/jsr305_annotations",
]

java_library(
    name = "dependency_information_collector_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "ComponentGeneratorProcessor.java",
            "NewDependencyCollector.java",
            "NewScopeCalculator.java",
            "NewInjectorGenerator.java",
            "NewComponentGenerator.java",
            "NewScopeSizer.java",
            "TreeScopeSizer.java",
            "TigerDaggerGeneratorProcessor.java",
        ],
    ),
    deps = COMMON_DEPS,
)

java_library(
    name = "component_generator_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "DependencyInformationCollectorProcessor.java",
            "NewInjectorInjector.java",
        ],
    ),
    resources = [
        "//java/com/google/android/apps/docs/tools/dagger/codegeneration/data:component_template.ftl",
    ],
    deps = COMMON_DEPS,
)

java_library(
    name = "tiger_dagger_generator_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "DependencyInformationCollectorProcessor.java",
            "ComponentGeneratorProcessor.java",
            "NewInjectorInjector.java",
            "NewComponentGenerator.java",
        ],
    ),
    deps = TIGER_DAGGER_DEPS,
)

java_library(
    name = "tiger_injector_generator_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "DependencyInformationCollectorProcessor.java",
        ],
    ),
    deps = COMMON_DEPS,
)

java_plugin(
    name = "dependency_information_collector_processor_plugin",
    processor_class = "tiger.DependencyInformationCollectorProcessor",
    deps = [
        ":dependency_information_collector_processor_lib",
    ],
)

java_plugin(
    name = "tiger_injector_generator_processor_plugin",
    processor_class = "tiger.ComponentGeneratorProcessor",
    deps = [
        ":tiger_injector_generator_processor_lib",
    ],
)

java_plugin(
    name = "component_generator_processor_plugin",
    processor_class = "tiger.ComponentGeneratorProcessor",
    deps = [
        ":component_generator_processor_lib",
    ],
)

java_plugin(
    name = "tiger_dagger_generator_processor_plugin",
    processor_class = "tiger.TigerDaggerGeneratorProcessor",
    deps = [
        ":tiger_dagger_generator_processor_lib",
    ],
)

ANNOTATION_SRCS = [
    "GenerationTriggerAnnotation.java",
    "PackageForGenerated.java",
    "ScopedComponentNames.java",
    "ScopeDependency.java",
]

java_library(
    name = "annotation",
    srcs = ANNOTATION_SRCS,
    constraints = [
        "android",
    ],
)

java_library(
    name = "auto_dagger",
    constraints = [
        "android",
    ],
    exported_plugins = [
        ":dependency_information_collector_processor_plugin",
        ":component_generator_processor_plugin",
    ],
    exports = [
        ":annotation",
    ],
)

java_library(
    name = "component_generator_processor",
    constraints = [
        "android",
    ],
    exported_plugins = [
        ":dependency_information_collector_processor_plugin",
        ":component_generator_processor_plugin",
    ],
)

java_library(
    name = "tiger_injector_generator_processor",
    srcs = [
        "GenerationTriggerAnnotation.java",
    ],
    constraints = [
        "android",
    ],
    exported_plugins = [
        #  ":dependency_information_collector_processor_plugin",
        ":tiger_injector_generator_processor_plugin",
    ],
)

java_library(
    name = "tiger_dagger_generator_processor",
    constraints = [
        "android",
    ],
    exported_plugins = [
        ":tiger_dagger_generator_processor_plugin",
    ],
)

# AndroidLint
load("/java/com/google/android/libraries/docs/build_defs/lint", "drive_lint_test")

drive_lint_test(
    libs = [":component_generator_processor_lib"],
)
