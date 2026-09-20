package com.toka.app.data.sync

import com.toka.app.data.TokenStore
import com.toka.app.data.api.MutationEnvelope
import com.toka.app.data.api.PushRequest
import com.toka.app.data.api.TokaApi
import com.toka.app.data.local.OutboxEntity
import com.toka.app.data.local.PersonEntity
import com.toka.app.data.local.TaskEntity
import com.toka.app.data.local.TemplateEntity
import com.toka.app.data.local.TokaDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * El sincronizador.
 *
 * Un ciclo es: subir la cola, después bajar los cambios. Ese orden importa — subir
 * primero hace que la bajada incluya el efecto de lo que acabamos de mandar, y el
 * estado local converge en un solo viaje en vez de dos.
 *
 * ── Resolución de conflictos ──
 *
 * Gana el servidor, siempre. Es la regla más simple que funciona para este dominio:
 * dos personas de la misma casa marcando la misma tarea no es un conflicto que valga
 * la pena fusionar, es una carrera donde alguien llegó primero. Cuando el servidor
 * responde 409 ("esta tarea ya estaba resuelta"), la mutación se descarta de la cola
 * y la bajada siguiente trae la verdad.
 *
 * Lo que NO se hace: reintentar indefinidamente algo que el servidor rechazó por
 * razones de negocio. Solo se reintentan los fallos de transporte.
 */
class SyncEngine(
    private val api: () -> TokaApi,
    private val dao: TokaDao,
    private val tokenStore: TokenStore
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    /** Cuántas escrituras están esperando para subir. Para mostrarlo en la UI. */
    val pendingCount: Flow<Int> = dao.pendingCount()

    /**
     * Encola una escritura. Devuelve el id de la mutación, que no cambia entre
     * reintentos: es lo que hace que reenviarla sea inofensivo.
     */
    suspend fun enqueue(op: String, payload: JsonElement): String {
        val id = UUID.randomUUID().toString()
        dao.enqueue(
            OutboxEntity(
                mutationId = id,
                op = op,
                payloadJson = json.encodeToString(JsonElement.serializer(), payload),
                createdAt = System.currentTimeMillis()
            )
        )
        return id
    }

    /** Un id local para una entidad creada sin conexión. */
    fun newClientId(): String = UUID.randomUUID().toString()

    /**
     * Un ciclo completo. Devuelve false si no se pudo hablar con el servidor, para
     * que quien llame decida si reintentar.
     */
    suspend fun sync(): Result<Unit> = mutex.withLock {
        runCatching {
            val token = tokenStore.tokenFlow.first()
                ?: return@runCatching // sin sesión no hay nada que sincronizar
            val auth = "Bearer $token"

            push(auth)
            pull(auth)
        }
    }

    private suspend fun push(auth: String) {
        while (true) {
            val batch = dao.outboxBatch(BATCH)
            if (batch.isEmpty()) return

            val envelopes = batch.map {
                MutationEnvelope(
                    mutationId = it.mutationId,
                    op = it.op,
                    payload = json.parseToJsonElement(it.payloadJson)
                )
            }

            // Si esto lanza (red caída, 5xx), la cola queda intacta y se reintenta
            // en el ciclo siguiente con los mismos mutation_id.
            val response = api().push(PushRequest(envelopes), auth)

            val byId = response.results.associateBy { it.mutationId }
            for (entry in batch) {
                val result = byId[entry.mutationId]
                when {
                    result == null ->
                        // El servidor no dijo nada de esta mutación. Se deja en la
                        // cola: reenviarla es seguro.
                        dao.markAttempt(entry.mutationId, "sin respuesta del servidor")

                    result.status in 200..299 || result.duplicate ->
                        dao.dequeue(entry.mutationId)

                    result.status == 409 ->
                        // Alguien se adelantó. No es un error a reintentar.
                        dao.dequeue(entry.mutationId)

                    result.status in 400..499 -> {
                        // Mutación inválida o sobre algo que ya no existe. Reintentarla
                        // la dejaría atascada bloqueando todo lo que viene detrás.
                        dao.dequeue(entry.mutationId)
                    }

                    else ->
                        dao.markAttempt(entry.mutationId, "status ${result.status}")
                }
            }

            if (batch.size < BATCH) return
        }
    }

    private suspend fun pull(auth: String) {
        val since = dao.syncState()?.cursor ?: 0L
        val page = api().pull(since, auth)

        dao.applyPull(
            people = page.people.map {
                PersonEntity(
                    id = it.id,
                    householdId = it.householdId,
                    name = it.name,
                    color = it.color,
                    avatarEmoji = it.avatarEmoji,
                    rowVersion = it.rowVersion
                )
            },
            templates = page.templates.map {
                TemplateEntity(
                    id = it.id,
                    householdId = it.householdId,
                    name = it.name,
                    description = it.description,
                    recurrenceDays = it.recurrenceDays,
                    preferredAssigneeId = it.preferredAssigneeId,
                    reminderTimes = it.reminderTimes,
                    isActive = it.isActive,
                    rowVersion = it.rowVersion,
                    clientId = it.clientId,
                    pending = false
                )
            },
            tasks = page.tasks.map {
                TaskEntity(
                    id = it.id,
                    templateId = it.templateId,
                    householdId = it.householdId,
                    status = it.status,
                    dueAt = it.dueAt,
                    assignedToId = it.assignedToId,
                    completedById = it.completedById,
                    completedAt = it.completedAt,
                    notes = it.notes,
                    templateName = it.templateName,
                    rowVersion = it.rowVersion,
                    clientId = it.clientId,
                    pending = false
                )
            },
            deleted = page.deleted.map { it.entity to it.id },
            cursor = page.cursor
        )

        // Las filas provisionales que el servidor confirmó con otro id (una plantilla
        // creada sin conexión llega con id negativo local y vuelve con el id real).
        reconcileProvisional(page.templates.mapNotNull { t -> t.clientId?.let { it to t.id } })
    }

    /**
     * Borra las filas provisionales cuyo client_id ya volvió con un id de servidor.
     *
     * Mientras una creación está en la cola, la fila vive localmente con un id
     * negativo para que la pantalla la muestre de inmediato. Cuando el servidor la
     * confirma llega la misma entidad con su id definitivo, y hay que quitar la
     * provisional o aparecería duplicada.
     */
    private suspend fun reconcileProvisional(confirmed: List<Pair<String, Long>>) {
        for ((clientId, _) in confirmed) {
            dao.deleteTemplate(provisionalIdFor(clientId))
        }
    }

    /** Los ids provisionales son negativos: no pueden chocar con los del servidor. */
    fun provisionalIdFor(clientId: String): Long =
        -(clientId.hashCode().toLong() and 0x7fffffffL) - 1

    suspend fun reset() = dao.clearAll()

    private companion object {
        const val BATCH = 100
    }
}

/** Azúcar para leer un campo de un JsonElement sin ceremonia. */
internal fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
