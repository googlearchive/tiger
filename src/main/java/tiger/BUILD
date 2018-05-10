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
    #"//java/com/google/android/apps/docs/tools/dagger/componentfactory",
    #"//java/com/google/android/libraries/docs/inject/annotations",
    "//java/com/google/common/base",
    "//java/com/google/common/collect",
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
    "//third_party/java/dagger:dagger",
    "//third_party/java/javapoet",
    "//third_party/java/jsr330_inject",
    "//third_party/java/jsr305_annotations",
]

TIGER_DAGGER_DEPS = [
    #    "//java/com/google/android/apps/docs/tools/dagger/codegeneration:binding_checker_lib",
    #"//java/com/google/android/apps/docs/tools/dagger/codegeneration:utilities",
    #    "//java/com/google/android/apps/docs/tools/dagger/componentfactory",
    #"//java/com/google/android/apps/docs/tools/dagger/componentfactory:annotations",
    #"//java/com/google/android/libraries/docs/inject/annotations",
    #    "//third_party/java/android_libs/guava_jdk5:annotations",
    #    "//third_party/java/android_libs/guava_jdk5:base",
    #    "//third_party/java/android_libs/guava_jdk5:collect",
    #    "//third_party/java/android_libs/guava_jdk5:hash",
    #"//java/com/google/android/apps/docs/tools/freemarker:freemarker_custom_file_template_loader_lib",
    "//java/com/google/common/base",
    "//java/com/google/common/collect",
    "//third_party/java/auto:auto_service",
    "//third_party/java/auto:common",
    "//third_party/java/gson",
    "//third_party/java/javapoet",
    "//third_party/java_src/dagger/project/java/dagger:core",
    "//third_party/java/jsr330_inject",
    "//third_party/java/freemarker",
    "//third_party/java/jsr305_annotations",
    "//third_party/java/android/android_sdk_linux:android_runtime",
    "//third_party/java/flogger:android",
]

java_library(
    name = "dependency_information_collector_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "ComponentGeneratorProcessor.java",
            "DependencyCollector.java",
            "ScopeCalculator.java",
            "CoreInjectorGenerator.java",
            "ScopeSizer.java",
            "TreeScopeSizer.java",
            "TigerDaggerGeneratorProcessor.java",
            "Tiger2Processor.java",
            "ProxyProcessor.java",
            "Tiger3ProcessorForPackaged.java",
            "Tiger3ProcessorForComponent.java",
            "HubInjectorGenerator.java",
            "GeneralInjectorGeneratorHubClone.java",
        ],
    ),
    deps = COMMON_DEPS + [
        "//third_party/java_src/google_common/current/java/com/google/common/base",
        "//third_party/java_src/google_common/current/java/com/google/common/collect",
        "//third_party/java_src/google_common/current/java/com/google/common/io",
    ],
)

java_library(
    name = "tiger_injector_generator_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "DependencyInformationCollectorProcessor.java",
            "TigerDaggerGeneratorProcessor.java",
            "ProxyProcessor.java",
            "Tiger2Processor.java",
            "Tiger3ProcessorForPackaged.java",
            "Tiger3ProcessorForComponent.java",
            "HubInjectorGenerator.java",
            "GeneralInjectorGeneratorHubClone.java",
        ],
    ),
    deps = COMMON_DEPS + [
        "//third_party/java/jsr250_annotations",
        "//third_party/java_src/flogger/google:flogger",
        "//third_party/java_src/google_common/current/java/com/google/common/base",
        "//third_party/java_src/google_common/current/java/com/google/common/collect",
        "//third_party/java_src/google_common/current/java/com/google/common/reflect",
    ],
)

java_library(
    name = "tiger_dagger_generator_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "DependencyInformationCollectorProcessor.java",
            "ComponentGeneratorProcessor.java",
            "ProxyProcessor.java",
            "Tiger2Processor.java",
            "Tiger3ProcessorForPackaged.java",
            "Tiger3ProcessorForComponent.java",
            "HubInjectorGenerator.java",
            "GeneralInjectorGeneratorHubClone.java",
        ],
    ),
    #    constraints = ["android"],
    deps = TIGER_DAGGER_DEPS + [
        "//third_party/java/jsr250_annotations",
        "//third_party/java_src/google_common/current/java/com/google/common/base",
        "//third_party/java_src/google_common/current/java/com/google/common/collect",
    ],
)

java_library(
    name = "tiger2_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "TigerDaggerGeneratorProcessor.java",
            "DependencyInformationCollectorProcessor.java",
            "ComponentGeneratorProcessor.java",
            "ProxyProcessor.java",
            "Tiger3ProcessorForPackaged.java",
            "Tiger3ProcessorForComponent.java",
            "HubInjectorGenerator.java",
            "GeneralInjectorGeneratorHubClone.java",
        ],
    ),
    #    constraints = ["android"],
    deps = TIGER_DAGGER_DEPS + [
        "//third_party/java/jsr250_annotations",
        "//third_party/java_src/google_common/current/java/com/google/common/base",
        "//third_party/java_src/google_common/current/java/com/google/common/collect",
    ],
)

java_library(
    name = "proxy_processor_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "TigerDaggerGeneratorProcessor.java",
            "DependencyInformationCollectorProcessor.java",
            "ComponentGeneratorProcessor.java",
            "Tiger2Processor.java",
            "Tiger3ProcessorForComponent.java",
            "HubInjectorGenerator.java",
            "GeneralInjectorGeneratorHubClone.java",
        ],
    ),
    #    constraints = ["android"],
    deps = TIGER_DAGGER_DEPS + [
        "//third_party/java/jsr250_annotations",
        "//third_party/java_src/google_common/current/java/com/google/common/base",
        "//third_party/java_src/google_common/current/java/com/google/common/collect",
    ],
)

java_library(
    name = "tiger3_processor_for_packaged_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "TigerDaggerGeneratorProcessor.java",
            "DependencyInformationCollectorProcessor.java",
            "ComponentGeneratorProcessor.java",
            "Tiger2Processor.java",
            "ProxyProcessor.java",
            "Tiger3ProcessorForComponent.java",
            "HubInjectorGenerator.java",
            "GeneralInjectorGeneratorHubClone.java",
        ],
    ),
    #    constraints = ["android"],
    deps = TIGER_DAGGER_DEPS + [
        "//third_party/java/jsr250_annotations",
        "//third_party/java_src/google_common/current/java/com/google/common/base",
        "//third_party/java_src/google_common/current/java/com/google/common/collect",
    ],
)

java_library(
    name = "tiger3_processor_for_component_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "TigerDaggerGeneratorProcessor.java",
            "DependencyInformationCollectorProcessor.java",
            "ComponentGeneratorProcessor.java",
            "Tiger2Processor.java",
            "ProxyProcessor.java",
        ],
    ),
    #    constraints = ["android"],
    deps = TIGER_DAGGER_DEPS + [
        "//third_party/java/jsr250_annotations",
        "//third_party/java_src/google_common/current/java/com/google/common/base",
        "//third_party/java_src/google_common/current/java/com/google/common/collect",
    ],
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
    name = "tiger_dagger_generator_processor_plugin",
    generates_api = 1,
    processor_class = "tiger.TigerDaggerGeneratorProcessor",
    deps = [
        ":tiger_dagger_generator_processor_lib",
    ],
)

java_plugin(
    name = "tiger2_processor_plugin",
    generates_api = 1,
    processor_class = "tiger.Tiger2Processor",
    deps = [
        ":tiger2_processor_lib",
    ],
)

java_plugin(
    name = "proxy_processor_plugin",
    generates_api = 1,
    processor_class = "tiger.ProxyProcessor",
    deps = [
        ":proxy_processor_lib",
    ],
)

java_plugin(
    name = "tiger3_processor_for_packaged_plugin",
    generates_api = 1,
    processor_class = "tiger.Tiger3ProcessorForPackaged",
    deps = [
        ":tiger3_processor_for_packaged_lib",
    ],
)

java_plugin(
    name = "tiger3_processor_for_component_plugin",
    generates_api = 1,
    processor_class = "tiger.Tiger3ProcessorForComponent",
    deps = [
        ":tiger3_processor_for_component_lib",
    ],
)

ANNOTATION_SRCS = [
    "GenerationTriggerAnnotation.java",
    "MembersInjector.java",
    "PackageForGenerated.java",
    "ScopedComponentNames.java",
    "ScopeDependency.java",
    "Tail.java",
]

java_library(
    name = "annotation",
    srcs = ANNOTATION_SRCS,
    constraints = [
        "android",
    ],
)

java_library(
    name = "component_generator_processor",
    constraints = [
        #    "android",
    ],
    exported_plugins = [
        ":dependency_information_collector_processor_plugin",
        ":tiger_injector_generator_processor_plugin",
    ],
    exports = [
        ":annotation",
        ":tiger_dagger_generator_processor_lib",
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
    tags = [
        "keep_dep",
    ],
    exports = [
        #            ":tiger_dagger_generator_processor_lib",
        ":annotation",
        "//third_party/java_src/dagger/project/java/dagger:core",
    ],
)

java_library(
    name = "tiger2_processor",
    constraints = [
        "android",
    ],
    exported_plugins = [
        ":tiger2_processor_plugin",
        ":proxy_processor_plugin",
    ],
    tags = [
        "keep_dep",
    ],
    exports = [
        #            ":tiger_dagger_generator_processor_lib",
        ":annotation",
        "//third_party/java_src/dagger/project/java/dagger:core",
        "//third_party/java/jsr250_annotations",
    ],
)

java_library(
    name = "proxy_processor",
    constraints = [
        "android",
    ],
    exported_plugins = [
        ":proxy_processor_plugin",
    ],
    tags = [
        "keep_dep",
    ],
    exports = [
        #            ":tiger_dagger_generator_processor_lib",
        ":annotation",
        "//third_party/java_src/dagger/project/java/dagger:core",
    ],
)

java_library(
    name = "tiger3",
    constraints = [
        "android",
    ],
    exported_plugins = [
        ":tiger3_processor_for_packaged_plugin",
    ],
    tags = [
        "keep_dep",
    ],
    exports = [
        #            ":tiger_dagger_generator_processor_lib",
        ":annotation",
        "//third_party/java_src/dagger/project/java/dagger:core",
        "//third_party/java/jsr250_annotations",
    ],
)

java_library(
    name = "tiger3_for_component",
    constraints = [
        "android",
    ],
    exported_plugins = [
        ":tiger3_processor_for_component_plugin",
    ],
    tags = [
        "keep_dep",
    ],
    exports = [
        #            ":tiger_dagger_generator_processor_lib",
        ":annotation",
        "//third_party/java_src/dagger/project/java/dagger:core",
        "//third_party/java/jsr250_annotations",
    ],
)

java_library(
    name = "tiger",
    constraints = [
        "android",
    ],
    exports = [
        #        ":tiger3",
        ":tiger3_for_component",
    ],
)

java_library(
    name = "export_dagger_api",
    constraints = [
        "android",
    ],
    exports = [
        #            ":tiger_dagger_generator_processor_lib",
        "//third_party/java_src/dagger/project/java/dagger:core",
    ],
)

# AndroidLint
#load("/java/com/google/android/libraries/docs/build_defs/lint", "drive_lint_test")

#drive_lint_test(
#    libs = [":component_generator_processor_lib"],
#)
