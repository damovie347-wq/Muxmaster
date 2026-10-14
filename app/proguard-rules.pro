# ffmpeg-kit uses JNI + reflection; keep its classes intact if minification is ever enabled.
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
