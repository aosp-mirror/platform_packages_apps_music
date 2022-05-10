load("@rules_android//rules:rules.bzl", "android_binary", "android_library")

android_binary(
    name = "Music",
    srcs = glob(["src/**/*.java"]),
    custom_package = "com.android.music",
    manifest = "AndroidManifest.xml",
    deps = ["//packages/apps/Music/kotlin:MusicResources"],
)
