# TheftGuard Super-Lite Production Rules

# Optimized Keep Rules
-keep class com.systemguard.mobi.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class androidx.camera.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.work.** { *; }

# Remove unused resources but keep manifest entries
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.admin.DeviceAdminReceiver

# Basic optimizations to reduce size
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# Remove debug logs and other junk
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
