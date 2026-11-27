# Keep kotlinx.serialization serializers and DTO models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.yaytif.app.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.yaytif.app.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.yaytif.app.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit service interfaces
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep class com.yaytif.app.data.remote.YaytifApiService { *; }
