plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Procesa google-services.json (proyecto de Firebase, cliente web de Google Sign-In).
    id("com.google.gms.google-services") version "4.5.0" apply false
}
