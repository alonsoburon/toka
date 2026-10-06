package com.toka.app.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.toka.app.BuildConfig

/**
 * Instancias únicas de Auth y Firestore. En debug apuntan a los emuladores locales
 * (scripts/dev.sh), con puertos propios de Toka: los de Finanzas son 9099/8085.
 */
object FirebaseSetup {
    const val AUTH_EMULATOR_PORT = 9199
    const val FIRESTORE_EMULATOR_PORT = 8185

    val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance().apply {
            if (BuildConfig.EMULATOR_HOST.isNotEmpty()) useEmulator(BuildConfig.EMULATOR_HOST, AUTH_EMULATOR_PORT)
        }
    }

    val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            // useEmulator y los ajustes tienen que ir antes de cualquier uso.
            if (BuildConfig.EMULATOR_HOST.isNotEmpty()) useEmulator(BuildConfig.EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
            // Caché persistente: las escrituras sin conexión se guardan y suben solas.
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().setSizeBytes(100L * 1024 * 1024).build())
                .build()
        }
    }
}
