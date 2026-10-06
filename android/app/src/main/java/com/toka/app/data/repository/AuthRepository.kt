package com.toka.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class AuthRepository(private val auth: FirebaseAuth) {

    val user: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    val current: FirebaseUser? get() = auth.currentUser

    suspend fun signInWithGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    /**
     * Solo desarrollo local: el emulador de Auth acepta un ID token de Google falso (JSON sin firmar),
     * así se entra como [email] sin cuenta real. Contra Firebase real esto falla.
     */
    suspend fun signInDev(email: String, name: String) {
        val fakeToken = JSONObject()
            .put("sub", "dev-" + email.substringBefore('@'))
            .put("email", email)
            .put("email_verified", true)
            .put("name", name)
            .toString()
        auth.signInWithCredential(GoogleAuthProvider.getCredential(fakeToken, null)).await()
    }

    fun signOut() = auth.signOut()
}
