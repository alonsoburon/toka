plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // KSP genera el código de Room. Desde KSP 2.3 su versión ya no lleva el prefijo de
    // Kotlin: 2.3.x sirve para Kotlin 2.3.x.
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
