# ffmpeg-kit uses JNI + reflection; keep its classes intact if minification is ever enabled.
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Media3 extractors and renderers
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class org.checkerframework.** { *; }
-dontwarn org.checkerframework.**

# Keep WebView JavascriptInterface methods for Google Drive stream resolver in release builds
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
