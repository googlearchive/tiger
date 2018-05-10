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
# Copyright 2008 Google Inc. All Rights Reserved.
#
# Description:
#   Sample project who jar is used by test case.

package(default_visibility = ["//visibility:legacy_public"])

java_library(
    name = "sample_lib",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "build/**/*.java",
            "src/main/java/sample/MainExtra.java",
        ],
    ),
    constraints = ["android"],
    deps = [
        "//experimental/users/freemanliu/tiger/src/main/java/tiger:annotation",
        "//java/com/google/common/base:base-android",
        "//third_party/java/android_libs/guava_jdk5:base",
        "//third_party/java/dagger",
        "//third_party/java/dagger:android",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
    ],
)

#java_library(
#    name = "sample_lib_tiger",
#    srcs = glob(
#        ["**/*.java"],
#        exclude = [
#            "build/**/*.java",
#        ],
#    ),
#    deps = [
#        "//experimental/users/freemanliu/tiger/src/main/java/tiger:component_generator_processor",
#        "//third_party/java/jsr305_annotations",
#        "//third_party/java/jsr330_inject",
#        "//third_party/java_src/dagger/project/java/dagger:core",
#    ],
#)

java_library(
    name = "sample_lib_tiger_dagger_compatible",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "build/**/*.java",
            "src/main/java/sample/MainExtra.java",
        ],
    ),
    constraints = ["android"],
    deps = [
        #        "//experimental/users/freemanliu/tiger/src/main/java/tiger:tiger_dagger_generator_processor",
        #        "//experimental/users/freemanliu/tiger/src/main/java/tiger:tiger2_processor",
        "//experimental/users/freemanliu/tiger/src/main/java/tiger:tiger3",
        #        "//experimental/users/freemanliu/tiger/src/main/java/tiger:proxy_processor",
        "//java/com/google/common/base:base-android",
        "//third_party/java/android_libs/guava_jdk5:base",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/dagger:android",

        #        "//third_party/java_src/dagger/project/java/dagger:core",
    ],
)

java_library(
    name = "main_lib",
    srcs = [
        "src/main/java/sample/MainExtra.java",
    ],
    #main_class = "sample.MainExtra",
    constraints = ["android"],
    deps = [
        ":sample_lib_tiger_dagger_compatible",
        #":sample_lib",
        #        "//third_party/java_src/dagger/project/java/dagger:core",
    ],
)

#SAMPLE_SRC_WITHOUT_DAGGER = glob(
#    ["*.java"],
#    exclude = [
#        "Main.java",
#        "ActivityComponent.java",
#        "PseudoActivity.java",
#    ],
#)
#
#java_library(
#    name = "sample_lib_without_dagger",
#    srcs = SAMPLE_SRC_WITHOUT_DAGGER,
#    deps = [
#        "//third_party/java/dagger:dagger_components_ide",
#        "//third_party/java/jsr305_annotations",
#        "//third_party/java/jsr330_inject",
#    ],
#)

java_binary(
    name = "sample",
    main_class = "sample.Main",
    runtime_deps = [":sample_lib"],
)

#java_binary(
#    name = "sample_tiger",
#    main_class = "sample.Main",
#    runtime_deps = [":sample_lib_tiger"],
#)

java_binary(
    name = "sample_tiger_dagger_compatible",
    main_class = "sample.Main",
    runtime_deps = [":sample_lib_tiger_dagger_compatible"],
)
