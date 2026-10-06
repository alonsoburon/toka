package com.toka.app.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.toka.app.data.model.HouseholdDTO
import com.toka.app.data.model.PersonDTO
import com.toka.app.data.model.TaskDTO
import com.toka.app.data.model.TemplateDTO
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import com.google.firebase.firestore.FirebaseFirestoreException
import java.security.MessageDigest
import java.time.Instant

// ── Rutas (ver el modelo en CLAUDE.md y firestore.rules) ──────────────────────

fun FirebaseFirestore.user(uid: String): DocumentReference = collection("users").document(uid)
fun FirebaseFirestore.invite(code: String): DocumentReference = collection("invites").document(code)
fun FirebaseFirestore.households(): CollectionReference = collection("households")
fun FirebaseFirestore.household(hid: String): DocumentReference = households().document(hid)
fun FirebaseFirestore.people(hid: String): CollectionReference = household(hid).collection("people")
fun FirebaseFirestore.templates(hid: String): CollectionReference = household(hid).collection("templates")
fun FirebaseFirestore.tasks(hid: String): CollectionReference = household(hid).collection("tasks")

// ── Flows ────────────────────────────────────────────────────────────────────

/** Listener en tiempo real de un documento. Con [metadata] avisa también cuando cambia el estado de sincronización. */
fun DocumentReference.asFlow(metadata: Boolean = false): Flow<DocumentSnapshot> = callbackFlow {
    val changes = if (metadata) MetadataChanges.INCLUDE else MetadataChanges.EXCLUDE
    val reg = addSnapshotListener(changes) { snap, err ->
        if (err != null) close(err) else if (snap != null) trySend(snap)
    }
    awaitClose { reg.remove() }
}

/** Listener en tiempo real de una consulta. */
fun Query.asFlow(metadata: Boolean = false): Flow<QuerySnapshot> = callbackFlow {
    val changes = if (metadata) MetadataChanges.INCLUDE else MetadataChanges.EXCLUDE
    val reg = addSnapshotListener(changes) { snap, err ->
        if (err != null) close(err) else if (snap != null) trySend(snap)
    }
    awaitClose { reg.remove() }
}

/**
 * Un listener que recibe PERMISSION_DENIED muere para siempre. Justo después de crear/unirse a un hogar
 * puede abrirse un instante antes de que el servidor registre la membresía, así que se reintenta unas
 * veces con espera creciente. Al cerrar sesión el error persiste y el flujo termina igual.
 */
fun <T> Flow<T>.retryOnPermissionDenied(maxAttempts: Int = 6): Flow<T> = retryWhen { cause, attempt ->
    val denied = cause is FirebaseFirestoreException && cause.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    if (denied && attempt < maxAttempts) {
        delay(500L * (attempt + 1))
        true
    } else {
        false
    }
}

// ── Mapeo documento → modelo ─────────────────────────────────────────────────

fun Timestamp.toIso(): String = Instant.ofEpochSecond(seconds, nanoseconds.toLong()).toString()
fun String.toTimestamp(): Timestamp = Instant.parse(this).let { Timestamp(it.epochSecond, it.nano) }

fun DocumentSnapshot.toPerson() = PersonDTO(
    id = id,
    name = getString("name") ?: "",
    color = getString("color") ?: "#a78bfa",
    avatarEmoji = getString("emoji") ?: "🐣"
)

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.toHousehold() = HouseholdDTO(
    id = id,
    name = getString("name") ?: "",
    inviteCode = getString("inviteCode") ?: "",
    memberIds = (get("members") as? List<String>) ?: emptyList()
)

fun DocumentSnapshot.toTemplate() = TemplateDTO(
    id = id,
    name = getString("name") ?: "",
    description = getString("description"),
    recurrenceDays = getLong("recurrenceDays")?.toInt(),
    preferredAssigneeId = getString("preferredAssigneeId"),
    reminderTimes = getString("reminderTimes"),
    isActive = getBoolean("isActive") ?: true,
    triggerTemplateId = getString("triggerTemplateId"),
    triggerDelayDays = getLong("triggerDelayDays")?.toInt()
)

/** La asignada y quien completó se resuelven contra la lista de personas del hogar. */
fun DocumentSnapshot.toTask(people: Map<String, PersonDTO>): TaskDTO {
    val assignee = getString("assignedToId")?.let { people[it] }
    val completer = getString("completedById")?.let { people[it] }
    return TaskDTO(
        id = id,
        templateId = getString("templateId"),
        status = getString("status") ?: "pending",
        dueAt = getTimestamp("dueAt")?.toIso(),
        assignedToId = getString("assignedToId"),
        completedById = getString("completedById"),
        completedAt = getTimestamp("completedAt")?.toIso(),
        notes = getString("notes"),
        templateName = getString("templateName"),
        assignedToName = assignee?.name,
        assignedToColor = assignee?.color,
        assignedToEmoji = assignee?.avatarEmoji,
        completedByName = completer?.name,
        completedByColor = completer?.color,
        completedByEmoji = completer?.avatarEmoji,
        pendingSync = metadata.hasPendingWrites()
    )
}

/**
 * Id de la instancia que genera la recurrencia al resolver [taskId]. Es determinista a propósito: si dos
 * teléfonos completan la misma tarea sin conexión, ambos escriben el MISMO documento y no se duplica.
 * Es un hash (y no `<id>_next`) para que no crezca en cada vuelta de la cadena de recurrencia.
 */
fun nextTaskId(taskId: String): String =
    "n" + MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)

/** Id de la tarea que [followerTemplateId] genera al completar [taskId]; determinista como [nextTaskId]. */
fun followUpTaskId(taskId: String, followerTemplateId: String): String = nextTaskId("$taskId:$followerTemplateId")
