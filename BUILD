load("//build/bazel/rules/android:rules.bzl", "android_binary")

android_binary(
    name = "Music",
    srcs = glob(["src/**/*.java"]),
    custom_package = "com.android.music",
    manifest = "AndroidManifest.xml",
    # TODO(b/179889880): this manual BUILD file exists because these resources,
    # if listed as files, would cross package boundary.
    resource_files = ["//packages/apps/Music/kotlin:MusicResourceFiles"],
    sdk_version = "current",
    target_compatible_with = ["//build/bazel/platforms/os:android"],
)
