package com.toka.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.toka.app.data.firebase.asFlow
import com.toka.app.data.firebase.household
import com.toka.app.data.firebase.households
import com.toka.app.data.firebase.invite
import com.toka.app.data.firebase.people
import com.toka.app.data.firebase.retryOnPermissionDenied
import com.toka.app.data.firebase.toHousehold
import com.toka.app.data.firebase.toPerson
import com.toka.app.data.firebase.user
import com.toka.app.data.model.HouseholdDTO
import com.toka.app.data.model.PersonDTO
import com.toka.app.data.suspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

class HouseholdException(message: String) : Exception(message)

/**
 * El hogar y sus integrantes. Las personas ya no se "agregan": cada integrante es una cuenta de
 * Google que se une con el código de invitación, y su id es su uid de Firebase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdRepository(
    private val db: FirebaseFirestore,
    private val session: SessionRepository,
    scope: CoroutineScope
) {
    private val householdId: Flow<String?> = session.session
        .map { (it as? Session.InHousehold)?.householdId }
        .distinctUntilChanged()

    val household: StateFlow<HouseholdDTO?> = householdId
        .flatMapLatest { hid ->
            if (hid == null) flowOf(null)
            else db.household(hid).asFlow().map { if (it.exists()) it.toHousehold() else null }
                .retryOnPermissionDenied()
                .catch { emit(null) } // al cerrar sesión el listener pierde permiso: no es un error
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val people: StateFlow<List<PersonDTO>> = householdId
        .flatMapLatest { hid ->
            if (hid == null) flowOf(emptyList())
            else db.people(hid).asFlow().map { snap -> snap.documents.map { it.toPerson() }.sortedBy { it.name.lowercase() } }
                .retryOnPermissionDenied()
                .catch { emit(emptyList()) }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Mi perfil dentro del hogar (null mientras carga). */
    fun me(uid: String): Flow<PersonDTO?> = people.map { list -> list.firstOrNull { it.id == uid } }

    /** Crea el hogar con su invitación y el perfil del creador. Necesita conexión (el código debe ser único). */
    suspend fun create(user: FirebaseUser, householdName: String, name: String, color: String, emoji: String): Result<String> =
        suspendCatching {
            online {
                val code = freeCode()
                val ref = db.households().document()
                val hid = ref.id
                // Lote 1: hogar + invitación (las reglas validan la invitación contra el hogar con getAfter).
                db.batch().apply {
                    set(
                        ref,
                        mapOf(
                            "name" to householdName.trim(),
                            "inviteCode" to code,
                            "members" to listOf(user.uid),
                            "createdBy" to user.uid,
                            "createdAt" to Timestamp.now()
                        )
                    )
                    set(db.invite(code), mapOf("householdId" to hid))
                }.commit().await()
                // Lote 2: perfil y, al final, el puntero del usuario (activa la sesión).
                db.batch().apply {
                    set(db.people(hid).document(user.uid), profile(name, color, emoji))
                    set(db.user(user.uid), mapOf("householdId" to hid))
                }.commit().await()
                hid
            }
        }

    /** Une al usuario a un hogar existente con el código. */
    suspend fun join(user: FirebaseUser, rawCode: String, name: String, color: String, emoji: String): Result<String> =
        suspendCatching {
            online {
                val inv = db.invite(normalizeCode(rawCode)).get(Source.SERVER).await()
                val hid = inv.getString("householdId") ?: throw HouseholdException("Código no válido")
                try {
                    // Primero la unión y el perfil, confirmados por el servidor; recién entonces el puntero
                    // users/{uid}, que es lo que activa la sesión. Si fueran un solo lote, la caché local
                    // vería el puntero al instante y los listeners se abrirían antes de que el servidor
                    // sepa que ya eres miembro (PERMISSION_DENIED y el listener muere).
                    db.batch().apply {
                        update(db.household(hid), "members", FieldValue.arrayUnion(user.uid))
                        set(db.people(hid).document(user.uid), profile(name, color, emoji))
                    }.commit().await()
                    db.user(user.uid).set(mapOf("householdId" to hid)).await()
                } catch (e: FirebaseFirestoreException) {
                    if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        throw HouseholdException("No se pudo unir: el hogar está lleno o el código ya no sirve")
                    }
                    throw e
                }
                hid
            }
        }

    /** Sale del hogar: se quita de los miembros y borra su perfil. Si es la última persona, no. */
    suspend fun leave(user: FirebaseUser): Result<Unit> = suspendCatching {
        val hid = (session.session.value as? Session.InHousehold)?.householdId ?: throw HouseholdException("No hay hogar activo")
        if ((household.value?.memberIds?.size ?: 0) <= 1) {
            throw HouseholdException("No puedes salir siendo la última persona del hogar")
        }
        online {
            db.batch().apply {
                update(db.household(hid), "members", FieldValue.arrayRemove(user.uid))
                delete(db.people(hid).document(user.uid))
                delete(db.user(user.uid))
            }.commit().await()
        }
    }

    /** Código nuevo: el viejo deja de servir. Necesita conexión. */
    suspend fun regenerateInvite(): Result<String> = suspendCatching {
        val hid = (session.session.value as? Session.InHousehold)?.householdId ?: throw HouseholdException("No hay hogar activo")
        val old = household.value?.inviteCode
        online {
            val code = freeCode()
            db.batch().apply {
                update(db.household(hid), "inviteCode", code)
                set(db.invite(code), mapOf("householdId" to hid))
                if (!old.isNullOrEmpty()) delete(db.invite(old))
            }.commit().await()
            code
        }
    }

    /** Edita el propio perfil. Sin await: sin conexión se aplica local y sincroniza después. */
    fun updateMyProfile(uid: String, name: String, color: String, emoji: String) {
        val hid = (session.session.value as? Session.InHousehold)?.householdId ?: return
        db.people(hid).document(uid).set(profile(name, color, emoji))
    }

    private fun profile(name: String, color: String, emoji: String) =
        mapOf("name" to name.trim().take(40), "color" to color, "emoji" to emoji)

    private suspend fun freeCode(): String {
        repeat(5) {
            val code = generateInviteCode()
            if (!db.invite(code).get(Source.SERVER).await().exists()) return code
        }
        throw HouseholdException("No se pudo generar un código, intenta de nuevo")
    }

    private suspend fun <T> online(block: suspend () -> T): T = try {
        withTimeout(20_000) { block() }
    } catch (e: TimeoutCancellationException) {
        throw HouseholdException("Sin conexión: esto necesita internet")
    } catch (e: FirebaseFirestoreException) {
        if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) throw HouseholdException("Sin conexión: esto necesita internet")
        throw e
    }

    companion object {
        // Sin 0/O, 1/I/L: se puede dictar sin confusiones. 6 caracteres (lo exigen las reglas).
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        fun generateInviteCode(): String = buildString { repeat(6) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

        /** Lo que escribe la persona: mayúsculas y sin espacios. */
        fun normalizeCode(text: String): String = text.uppercase().filter { it.isLetterOrDigit() }.take(6)
    }
}
