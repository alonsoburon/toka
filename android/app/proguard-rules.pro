# Reglas de R8 para el APK de release (isMinifyEnabled = true).
#
# Retrofit, OkHttp y kotlinx.serialization traen sus propias reglas de consumer, pero
# los workers de WorkManager no: se instancian por reflexión desde su base de datos y
# no aparecen en el manifest. Sin este keep, en release el sync en segundo plano y los
# recordatorios simplemente no corren.

-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}

# kotlinx.serialization: conservar los serializers generados de los DTOs.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.toka.app.**$$serializer { *; }
-keepclassmembers class com.toka.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.toka.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit: conservar la firma genérica y las anotaciones de los métodos de la API.
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface com.toka.app.data.api.TokaApi
