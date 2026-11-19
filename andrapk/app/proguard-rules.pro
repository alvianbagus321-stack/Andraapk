# Add project specific ProGuard / R8 rules here.
# Preserves stack traces for CrashReporter while enabling full R8 optimization
# and unused resource/icon shrinking (especially material-icons-extended).

-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Shizuku is accessed via reflection in AdbShizukuManager
-keep class dev.rikka.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn dev.rikka.shizuku.**
-dontwarn rikka.shizuku.**

# Keep Android Services, AccessibilityService, Activities, and Receivers
-keep public class com.example.MainActivity { *; }
-keep public class com.example.JarvisApp { *; }
-keep public class com.example.service.** extends android.app.Service { *; }
-keep public class com.example.service.** extends android.accessibilityservice.AccessibilityService { *; }
-keep public class com.example.service.** extends android.content.BroadcastReceiver { *; }
-keep public class com.example.ui.** extends android.app.Activity { *; }

# Keep data models used for JSON/Moshi serialization & Room
-keep class com.example.model.** { *; }

# Moshi & Kotlin metadata
-keep class com.squareup.moshi.** { *; }
-keep class * extends com.squareup.moshi.JsonAdapter { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# OkHttp / Retrofit / Coroutines / ML Kit safe rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn com.google.mlkit.**
-dontwarn com.google.firebase.**
