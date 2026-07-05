# TheftGuard Release Protection Rules

# CameraX rules
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }
-dontwarn androidx.camera.**

# JavaMail ( Gmail ) rules
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class myjava.awt.** { *; }
-keep class org.apache.harmony.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn java.awt.**

# Google Sign-In rules
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.**

# WorkManager rules
-keep class androidx.work.** { *; }

# Keep Lifecycle and Services
-keep class * extends android.app.Service
-keep class com.systemguard.mobi.MyAdminReceiver { *; }
-keep class com.systemguard.mobi.TheftGuardService { *; }
-keep class com.systemguard.mobi.RestartReceiver { *; }

# General optimizations
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontoptimize
-dontobfuscate
