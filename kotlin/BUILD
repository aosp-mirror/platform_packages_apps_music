load("@rules_android//rules:rules.bzl", "android_binary", "android_library")

# Placeholder manifest is required to build the android library below.
genrule(
    name = "gen_placeholdermanifest",
    outs = ["AndroidManifest.xml"],
    cmd = """
echo '<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.android.Music"
    android:versionCode="1"
    android:versionName="1.0" >

    <uses-sdk
        android:minSdkVersion="21"
        android:targetSdkVersion="21" />
</manifest>

' > $@""",
)

# Workaround a bug where including resources at the top-level android_binary fails,
# it seems due to the resource folder being nested. Instead, we create this
# library to hold the resources and make the android_binary target depend on it.
android_library(
    name = "MusicResources",
    srcs = [],
    custom_package = "com.android.music",
    manifest = "AndroidManifest.xml",
    resource_files = glob(["res/**"]),
    visibility = ["//visibility:public"],
)
