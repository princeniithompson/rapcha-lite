# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- Rapcha v23 release rules ----
# Keep line numbers so crash reports in Diagnostics stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WebView JavaScript bridge used during Google AI Studio key provisioning.
-keepclassmembers class com.princeniithompson.rapcha.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Services, receivers and the IME/accessibility entry points are referenced from the manifest
# (AAPT keeps them), listed here too so a future refactor cannot strip them by accident.
-keep class com.princeniithompson.rapcha.service.RapchaAccessibilityService { *; }
-keep class com.princeniithompson.rapcha.service.RapchaInputMethodService { *; }
-keep class com.princeniithompson.rapcha.service.FloatingBubbleService { <init>(); }
-keep class com.princeniithompson.rapcha.service.BubbleRecoveryJobService { <init>(); }
-keep class com.princeniithompson.rapcha.service.BubbleBootReceiver { <init>(); }

# Enums are persisted by name (valueOf) in the diagnostics JSONL and prefs.
-keepclassmembers enum com.princeniithompson.rapcha.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OkHttp / Okio optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Rapcha v25 lean ----
# Logging is a no-op in release: R8 removes android.util.Log calls (and the string building
# for their arguments when it has no other side effects) and the AppLogRepository no-op stubs.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
}
-assumenosideeffects class com.princeniithompson.rapcha.data.AppLogRepository {
    public void logEvent(...);
    public void addLog(...);
    public void updateLiveStats(...);
    public void updateConnectionState(...);
}
