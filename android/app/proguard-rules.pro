# Reglas de R8 para el APK de release (isMinifyEnabled = true).
#
# Firebase (Auth, Firestore), Credential Manager y Play Services traen sus propias reglas de consumer.
# Los workers de WorkManager no: se instancian por reflexión desde su base de datos y no aparecen en
# el manifest. Sin este keep, en release los recordatorios y las acciones de las notificaciones
# simplemente no corren.

-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}
