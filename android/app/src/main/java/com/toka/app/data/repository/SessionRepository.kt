package com.toka.app.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.toka.app.data.SessionCache
import com.toka.app.data.firebase.asFlow
import com.toka.app.data.firebase.user
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface Session {
    data object Loading : Session
    data object SignedOut : Session
    data class NoHousehold(val uid: String) : Session
    data class InHousehold(val uid: String, val householdId: String) : Session
    data class Failed(val message: String) : Session
}

/** Estado global: quién está logueado y a qué hogar pertenece (users/{uid}.householdId). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepository(
    auth: AuthRepository,
    db: FirebaseFirestore,
    private val cache: SessionCache,
    scope: CoroutineScope
) {
    val session: StateFlow<Session> = auth.user
        .flatMapLatest { user ->
            if (user == null) {
                flowOf(Session.SignedOut)
            } else {
                db.user(user.uid).asFlow(metadata = true)
                    .mapNotNull { snap ->
                        val hid = snap.getString("householdId")
                        when {
                            hid != null -> Session.InHousehold(user.uid, hid)
                            // Sin dato en caché y sin respuesta del servidor aún: seguimos cargando.
                            snap.metadata.isFromCache -> null
                            else -> Session.NoHousehold(user.uid)
                        }
                    }
                    .onStart { emit(Session.Loading) }
                    .catch { emit(Session.Failed(it.message ?: "Error al leer la sesión")) }
            }
        }
        .onEach { s ->
            when (s) {
                is Session.InHousehold -> cache.save(s.uid, s.householdId)
                is Session.NoHousehold -> cache.save(s.uid, null)
                Session.SignedOut -> cache.clear()
                else -> Unit
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, Session.Loading)
}
