# Android TV app ProGuard rules

# 保留 WebView JS 注入的 JavaScript 接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView / Activity 保持不被裁剪 (反射调用)
-keep class com.kvideo.tv.MainActivity { *; }
-keep class com.kvideo.tv.AppUpdater { *; }
-keep class com.kvideo.tv.UpdateDownloader { *; }
-keep class com.kvideo.tv.UpdateInfo { *; }

# org.json (Android 内置, 但保险)
-dontwarn org.json.**
