# ffmpeg-kit uses JNI + reflection; keep its classes intact if minification is ever enabled.
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Keep WebView JavascriptInterface methods for Google Drive stream resolver in release builds
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.muxmaster.data.DriveJsBridge { *; }
