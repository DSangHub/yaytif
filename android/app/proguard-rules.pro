# ==============================================================================
# Yaytif Release R8 / ProGuard Hardening Rules
# ==============================================================================

# Preserve stack trace metadata for Play Console deobfuscation with mapping.txt
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------------------
# Kotlinx Serialization & Yaytif Remote DTOs / BigDecimalSerializer
# ------------------------------------------------------------------------------
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.yaytif.app.data.remote.dto.** { *; }
-keep,includedescriptorclasses class com.yaytif.app.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.yaytif.app.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.yaytif.app.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.yaytif.app.data.remote.BigDecimalSerializer { *; }

# ------------------------------------------------------------------------------
# Retrofit & OkHttp
# ------------------------------------------------------------------------------
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.yaytif.app.data.remote.YaytifApiService { *; }
-keepclassmembers interface com.yaytif.app.data.remote.YaytifApiService {
    <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# ------------------------------------------------------------------------------
# Room Database, Entities, DAOs, and TypeConverters
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class com.yaytif.app.data.local.entity.** { *; }
-keep class com.yaytif.app.data.local.YaytifDatabase { *; }
-keep class com.yaytif.app.data.local.YaytifDatabase_Impl { *; }
-keep class com.yaytif.app.data.local.YaytifTypeConverters { *; }
-keep class com.yaytif.app.data.local.dao.** { *; }

# ------------------------------------------------------------------------------
# WorkManager Workers & WorkerFactory
# ------------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters, ...);
}
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters, ...);
}
-keep class com.yaytif.app.worker.** { *; }

# ------------------------------------------------------------------------------
# Android Services (NotificationListenerService & SponsoredOverlayService)
# ------------------------------------------------------------------------------
-keep public class * extends android.service.notification.NotificationListenerService { *; }
-keep public class * extends android.app.Service { *; }
-keep class com.yaytif.app.**NotificationListenerService* { *; }
-keep class com.yaytif.app.**SponsoredOverlayService* { *; }

# ------------------------------------------------------------------------------
# Strip verbose/debug/info android.util.Log calls in release builds
# (never risk leaking debug state or runtime context to logcat in production)
# ------------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
