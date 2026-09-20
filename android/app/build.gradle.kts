import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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

android {
    namespace = "com.toka.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.toka.app"
        minSdk = 33
        targetSdk = 35

        // El workflow de release pasa -PtokaVersionName / -PtokaVersionCode derivados
        // del tag (v1.2.3 -> 1.2.3 / 10203). En local queda el fallback.
        versionCode = (project.findProperty("tokaVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("tokaVersionName") as String?) ?: "0.1.0-dev"

        buildConfigField("String", "BASE_URL", "\"http://192.168.100.8:3000/\"")
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
        release {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Activity + Lifecycle
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // HTTP
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Persistencia local: SQLite es la fuente de verdad de la UI
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Sincronización en segundo plano, sobrevive al cierre de la app
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
