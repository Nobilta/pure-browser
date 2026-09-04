# Keep JNI methods for all native libraries
-keep class com.mybrowser.filter.NativeFilter { native <methods>; }
-keep class com.mybrowser.filter.CustomFilterController { native <methods>; }
-keep class com.mybrowser.download.NativeDownloader { native <methods>; }
-keep class com.mybrowser.core.UrlUtils { native <methods>; }
-keep class com.mybrowser.data.NativeCache { native <methods>; }
-keep class com.mybrowser.rust.FilenameParser { native <methods>; }

# Keep Compose runtime
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep WebView classes
-keep class android.webkit.** { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    <methods>;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    <methods>;
}

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep data classes
-keep class com.mybrowser.data.** { *; }
-keep class com.mybrowser.tabs.** { *; }

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
