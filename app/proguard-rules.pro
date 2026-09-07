# The optimized Android defaults already preserve names for reachable native methods, and
# every AndroidX dependency supplies its own consumer rules. Do not add package-wide keep
# rules here: retaining all of Compose previously prevented R8 from removing several
# megabytes of components that Pure Browser never calls. Manifest components and WebView
# overrides are discovered by the Android build tools and normal virtual dispatch.

# Scope the standard JNI name rule to application classes. It allows unused legacy JNI
# wrappers to be removed while preserving reachable Rust entry-point names.
-keepclasseswithmembernames,includedescriptorclasses class com.mybrowser.** {
    native <methods>;
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
