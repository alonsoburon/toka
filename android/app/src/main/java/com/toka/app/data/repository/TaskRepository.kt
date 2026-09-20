package com.toka.app.data.repository

import android.content.Context
import com.toka.app.data.api.CompleteTaskResponse
import com.toka.app.data.api.CreateTemplateRequest
import com.toka.app.data.api.TaskDTO
import com.toka.app.data.api.TemplateDTO
import com.toka.app.data.api.UpdateTemplateRequest
import com.toka.app.data.local.PersonEntity
import com.toka.app.data.local.TaskEntity
import com.toka.app.data.local.TemplateEntity
import com.toka.app.data.local.TokaDao
import com.toka.app.data.sync.SyncEngine
import com.toka.app.data.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Lee de SQLite y escribe en SQLite. La red no aparece en ningún camino que la
 * interfaz tenga que esperar.
 *
 * Una escritura hace dos cosas: aplica el cambio localmente para que la pantalla lo
 * refleje ya, y lo encola. Si la app se cierra en ese instante, la cola sigue ahí al
 * volver a abrirla.
 *
 * Se conservan las firmas `suspend ... Result<...>` que ya usaban los ViewModels
 * —ahora resueltas contra la base local— y se añaden las versiones Flow, que son las
 * que conviene usar de aquí en adelante: se actualizan solas cuando entra un sync.
 */
class TaskRepository(
    private val dao: TokaDao,
    private val sync: SyncEngine,
    private val appContext: Context
) {

    // ── Lecturas reactivas ────────────────────────────────────────────────────

    val pendingTasks: Flow<List<TaskDTO>> =
        combine(dao.pendingTasks(nowIso()), dao.people()) { tasks, people ->
            tasks.map { it.toDto(people) }
        }

    val history: Flow<List<TaskDTO>> =
        combine(dao.history(), dao.people()) { tasks, people ->
            tasks.map { it.toDto(people) }
        }

    val templates: Flow<List<TemplateDTO>> =
        dao.activeTemplates().map { list -> list.map { it.toDto() } }

    /** Escrituras esperando para subir. Para el indicador de "sin sincronizar". */
    val pendingSyncCount: Flow<Int> = sync.pendingCount

    fun taskFlow(taskId: Long): Flow<TaskDTO?> =
        combine(dao.task(taskId), dao.people()) { task, people -> task?.toDto(people) }

    // ── Lecturas puntuales (compatibilidad con los ViewModels actuales) ───────

    suspend fun getPendingTasks(): Result<List<TaskDTO>> = runCatching {
        val people = dao.peopleOnce()
        dao.pendingTasksOnce(nowIso()).map { it.toDto(people) }
    }

    suspend fun getHistory(days: Int = 30): Result<List<TaskDTO>> = runCatching {
        val cutoff = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val people = dao.peopleOnce()
        dao.historyOnce()
            .filter { row ->
                val at = row.completedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                at == null || at.isAfter(cutoff)
            }
            .map { it.toDto(people) }
    }

    suspend fun getTemplates(): Result<List<TemplateDTO>> = runCatching {
        dao.activeTemplatesOnce().map { it.toDto() }
    }

    suspend fun getTask(taskId: Long): Result<TaskDTO> = runCatching {
        val people = dao.peopleOnce()
        dao.taskOnce(taskId)?.toDto(people) ?: error("task $taskId not found")
    }

    suspend fun getTemplate(templateId: Long): Result<TemplateDTO> = runCatching {
        dao.activeTemplatesOnce().firstOrNull { it.id == templateId }?.toDto()
            ?: error("template $templateId not found")
    }

    // ── Escrituras: locales primero, cola después ─────────────────────────────

    suspend fun completeTask(taskId: Long, notes: String? = null): Result<CompleteTaskResponse> =
        resolve(taskId, "done", "task.complete", notes)

    suspend fun skipTask(taskId: Long): Result<CompleteTaskResponse> =
        resolve(taskId, "skipped", "task.skip", null)

    /**
     * Deshace un completado/saltado reciente. Vuelve la tarea a pending localmente y
     * encola `task.uncomplete`, que en el servidor además borra la instancia que la
     * recurrencia había generado.
     */
    suspend fun undoTask(taskId: Long): Result<Unit> = runCatching {
        val row = dao.taskOnce(taskId) ?: error("task $taskId not found")
        dao.upsertTask(
            row.copy(
                status = "pending",
                completedAt = null,
                pending = true
            )
        )
        sync.enqueue("task.uncomplete", buildJsonObject { put("id", JsonPrimitive(taskId)) })
        SyncWorker.syncNow(appContext)
    }

    private suspend fun resolve(
        taskId: Long,
        newStatus: String,
        op: String,
        notes: String?
    ): Result<CompleteTaskResponse> = runCatching {
        val row = dao.taskOnce(taskId) ?: error("task $taskId not found")
        val now = Instant.now().toString()

        dao.upsertTask(
            row.copy(
                status = newStatus,
                completedAt = now,
                notes = notes ?: row.notes,
                pending = true
            )
        )

        // completed_at viaja con la mutación: la tarea se marcó ahora, no cuando el
        // teléfono recupere señal. El servidor calcula desde ese momento la próxima
        // instancia, así que estar tres días sin conexión no corre la recurrencia.
        sync.enqueue(op, buildJsonObject {
            put("id", JsonPrimitive(taskId))
            put("completed_at", JsonPrimitive(now))
            put("notes", notes?.let { JsonPrimitive(it) } ?: JsonNull)
        })
        SyncWorker.syncNow(appContext)

        CompleteTaskResponse(status = newStatus, nextDueAt = null)
    }

    suspend fun updateTask(
        taskId: Long,
        assignedToId: Long? = null,
        notes: String? = null,
        dueAt: String? = null
    ): Result<Unit> = runCatching {
        val row = dao.taskOnce(taskId) ?: error("task $taskId not found")
        dao.upsertTask(
            row.copy(
                assignedToId = assignedToId ?: row.assignedToId,
                notes = notes ?: row.notes,
                dueAt = dueAt ?: row.dueAt,
                pending = true
            )
        )
        sync.enqueue("task.update", buildJsonObject {
            put("id", JsonPrimitive(taskId))
            put("assigned_to_id", assignedToId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("notes", notes?.let { JsonPrimitive(it) } ?: JsonNull)
            put("due_at", dueAt?.let { JsonPrimitive(it) } ?: JsonNull)
        })
        SyncWorker.syncNow(appContext)
    }

    suspend fun createTemplate(request: CreateTemplateRequest): Result<TemplateDTO> = runCatching {
        val clientId = sync.newClientId()
        val provisionalId = sync.provisionalIdFor(clientId)

        // Se inserta con un id negativo para que la pantalla la muestre al instante.
        // Cuando el servidor la confirme llegará con su id real y la provisional se
        // borra en la reconciliación del pull.
        val local = TemplateEntity(
            id = provisionalId,
            householdId = 0,
            name = request.name,
            description = request.description,
            recurrenceDays = request.recurrenceDays,
            preferredAssigneeId = request.preferredAssigneeId,
            reminderTimes = request.reminderTimes,
            isActive = true,
            rowVersion = 0,
            clientId = clientId,
            pending = true
        )
        dao.upsertTemplate(local)

        sync.enqueue("template.create", buildJsonObject {
            put("client_id", JsonPrimitive(clientId))
            put("name", JsonPrimitive(request.name))
            put("description", request.description?.let { JsonPrimitive(it) } ?: JsonNull)
            put("recurrence_days", request.recurrenceDays?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "preferred_assignee_id",
                request.preferredAssigneeId?.let { JsonPrimitive(it) } ?: JsonNull
            )
            put("reminder_times", request.reminderTimes?.let { JsonPrimitive(it) } ?: JsonNull)
        })
        SyncWorker.syncNow(appContext)

        local.toDto()
    }

    suspend fun updateTemplate(id: Long, request: UpdateTemplateRequest): Result<TemplateDTO> =
        runCatching {
            val row = dao.activeTemplatesOnce().firstOrNull { it.id == id }
                ?: error("template $id not found")
            val updated = row.copy(
                name = request.name ?: row.name,
                description = request.description ?: row.description,
                recurrenceDays = request.recurrenceDays ?: row.recurrenceDays,
                preferredAssigneeId = request.preferredAssigneeId ?: row.preferredAssigneeId,
                reminderTimes = request.reminderTimes ?: row.reminderTimes,
                pending = true
            )
            dao.upsertTemplate(updated)

            sync.enqueue("template.update", buildJsonObject {
                put("id", JsonPrimitive(id))
                put("name", request.name?.let { JsonPrimitive(it) } ?: JsonNull)
                put("description", request.description?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "recurrence_days",
                    request.recurrenceDays?.let { JsonPrimitive(it) } ?: JsonNull
                )
                put(
                    "preferred_assignee_id",
                    request.preferredAssigneeId?.let { JsonPrimitive(it) } ?: JsonNull
                )
                put("reminder_times", request.reminderTimes?.let { JsonPrimitive(it) } ?: JsonNull)
            })
            SyncWorker.syncNow(appContext)

            updated.toDto()
        }

    suspend fun deleteTemplate(id: Long): Result<Unit> = runCatching {
        dao.deleteTemplate(id)
        sync.enqueue("template.delete", buildJsonObject { put("id", JsonPrimitive(id)) })
        SyncWorker.syncNow(appContext)
    }

    /** Fuerza un ciclo y espera (para el gesto de deslizar para recargar). */
    suspend fun refresh(): Result<Unit> = sync.sync()

    /**
     * Tareas pendientes que vencen hoy y cuyo template tiene recordatorios. El worker
     * de notificaciones lo usa; los horarios son hora local del teléfono.
     */
    suspend fun reminderCandidates(): List<ReminderCandidate> = runCatching {
        val templates = dao.activeTemplatesOnce().associateBy { it.id }
        val today = LocalDate.now()
        dao.allPendingOnce().mapNotNull { task ->
            val times = task.templateId
                ?.let { templates[it]?.reminderTimes }
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: return@mapNotNull null
            if (times.isEmpty()) return@mapNotNull null

            val due = task.dueAt?.let { parseLocalDate(it) } ?: return@mapNotNull null
            // Avisa lo que vence hoy o ya está atrasado: una tarea de ayer es justo la
            // que más conviene recordar.
            if (due.isAfter(today)) return@mapNotNull null

            ReminderCandidate(
                taskId = task.id,
                name = task.templateName ?: "Tarea pendiente",
                times = times
            )
        }
    }.getOrDefault(emptyList())

    private fun nowIso(): String = Instant.now().toString()
}

data class ReminderCandidate(
    val taskId: Long,
    val name: String,
    val times: List<String>
)

private fun parseLocalDate(iso: String): LocalDate? = try {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
} catch (_: Exception) {
    null
}

private fun TaskEntity.toDto(people: List<PersonEntity>): TaskDTO {
    val assignee = people.firstOrNull { it.id == assignedToId }
    val completer = people.firstOrNull { it.id == completedById }
    return TaskDTO(
        id = id,
        templateId = templateId,
        householdId = householdId,
        status = status,
        dueAt = dueAt,
        assignedToId = assignedToId,
        completedById = completedById,
        completedAt = completedAt,
        notes = notes,
        templateName = templateName,
        assignedToName = assignee?.name,
        assignedToColor = assignee?.color,
        assignedToEmoji = assignee?.avatarEmoji,
        completedByName = completer?.name,
        completedByColor = completer?.color,
        completedByEmoji = completer?.avatarEmoji,
        rowVersion = rowVersion,
        clientId = clientId
    )
}

private fun TemplateEntity.toDto() = TemplateDTO(
    id = id,
    householdId = householdId,
    name = name,
    description = description,
    recurrenceDays = recurrenceDays,
    preferredAssigneeId = preferredAssigneeId,
    reminderTimes = reminderTimes,
    isActive = isActive,
    rowVersion = rowVersion,
    clientId = clientId
)
