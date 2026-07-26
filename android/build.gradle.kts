plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    // KSP genera el código de Room. La versión va atada a la de Kotlin: 2.1.0-1.0.29
    // es la que corresponde a Kotlin 2.1.0.
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}
