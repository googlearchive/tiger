android_library(
    name = "lib",
    srcs = glob(["**/*.java"]),
    manifest = "AndroidManifest.xml",
    resource_files = glob(["res/**"]),
    deps = [
        ":res",
        "//experimental/users/freemanliu/tiger/sample:sample_lib",
        "//third_party/java/android/android_sdk_linux:android_compiletime",
        "//third_party/java/android/android_sdk_linux/extras/android/compatibility/constraint_layout",
        "//third_party/java/android/android_sdk_linux/extras/android/compatibility/v4",
        "//third_party/java/android/android_sdk_linux/extras/android/compatibility/v7/appcompat",
        "//third_party/java/android_libs/guava_jdk5:base",
        "//third_party/java/android_libs/guava_jdk5:collect",
        "//third_party/java/android_libs/guava_jdk5:concurrent",
        "//third_party/java/android_libs/guava_jdk5:eventbus",
        "//third_party/java/dagger",
        "//third_party/java/dagger:android",
        "//third_party/java/dagger:android-support",
        "//third_party/java/flogger:android",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
    ],
)

android_library(
    name = "lib_tiger",
    srcs = glob(["**/*.java"]),
    manifest = "AndroidManifest.xml",
    resource_files = glob(["res/**"]),
    deps = [
        ":res",
        "//experimental/users/freemanliu/tiger/sample:sample_lib_tiger_dagger_compatible",
        #        "//experimental/users/freemanliu/tiger/src/main/java/tiger:tiger_dagger_generator_processor",
        "//experimental/users/freemanliu/tiger/src/main/java/tiger",
        "//third_party/java/android/android_sdk_linux:android_compiletime",
        "//third_party/java/android/android_sdk_linux/extras/android/compatibility/constraint_layout",
        "//third_party/java/android/android_sdk_linux/extras/android/compatibility/v4",
        "//third_party/java/android/android_sdk_linux/extras/android/compatibility/v7/appcompat",
        "//third_party/java/android_libs/guava_jdk5:base",
        "//third_party/java/android_libs/guava_jdk5:collect",
        "//third_party/java/android_libs/guava_jdk5:concurrent",
        "//third_party/java/android_libs/guava_jdk5:eventbus",
        "//third_party/java/dagger:android",
        "//third_party/java/dagger:android-support",
        "//third_party/java/flogger:android",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
    ],
)

android_library(
    name = "res",
    custom_package = "sample.tigerapp",
    manifest = "AndroidManifest.xml",
    resource_files = glob(["res/**"]),
    deps = [
        "//third_party/java/android/android_sdk_linux/extras/android/compatibility/constraint_layout",
        "//third_party/java/android/android_sdk_linux/extras/android/compatibility/v7/appcompat",
        "//third_party/java/android_libs/guava_jdk5:collect",
    ],
)

android_binary(
    name = "android_sample",
    manifest = "AndroidManifest.xml",
    deps = [
        ":lib",
        ":res",
    ],
)

android_binary(
    name = "android_sample_tiger",
    manifest = "AndroidManifest.xml",
    deps = [
        ":lib_tiger",
        ":res",
    ],
)
