import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Firma de release. En CI las credenciales llegan por variables de entorno; en local
// se pueden dejar en android/keystore.properties (gitignoreado). Sin credenciales el
// APK de release sale sin firmar, que es útil para `assembleRelease` en desarrollo.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(key: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(key)

// rootProject.file resuelve rutas relativas a android/ y deja pasar las absolutas
// (el CI apunta al keystore decodificado en /tmp).
val releaseStoreFile = secret("TOKA_KEYSTORE")?.let { rootProject.file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.toka.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.toka.app"
        minSdk = 33
        targetSdk = 36

        // El workflow de release pasa -PtokaVersionName / -PtokaVersionCode derivados
        // del tag (v1.2.3 -> 1.2.3 / 10203). En local queda el fallback.
        versionCode = (project.findProperty("tokaVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("tokaVersionName") as String?) ?: "0.1.0-dev"

    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = secret("TOKA_KEYSTORE_PASSWORD")
                keyAlias = secret("TOKA_KEY_ALIAS")
                keyPassword = secret("TOKA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Desarrollo local: el debug usa los emuladores de Firebase (scripts/dev.sh) y ofrece un login
        // falso, sin Google real. -Ptoka.emulador=<ip> para un teléfono en la LAN, o -Ptoka.emulador=
        // (vacío) para que el debug use Firebase real.
        debug {
            // Se instala junto a la app real ("Toka DEV"), sin pisar sus datos ni su widget.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            val host = (project.findProperty("toka.emulador") as String?) ?: "10.0.2.2"
            buildConfigField("String", "EMULATOR_HOST", "\"$host\"")
        }
        release {
            buildConfigField("String", "EMULATOR_HOST", "\"\"")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Activity + Lifecycle
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Firebase: Auth + Firestore (caché persistente: la app funciona sin conexión y sincroniza sola)
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // Entrar con Google (Credential Manager)
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")

    // Recordatorios en segundo plano (ya no hay cola de sincronización: la hace Firestore)
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
