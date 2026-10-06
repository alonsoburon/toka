package com.toka.app.data.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.toka.app.data.SessionCache
import com.toka.app.data.firebase.asFlow
import com.toka.app.data.firebase.followUpTaskId
import com.toka.app.data.firebase.nextTaskId
import com.toka.app.data.firebase.retryOnPermissionDenied
import com.toka.app.data.firebase.tasks
import com.toka.app.data.firebase.templates
import com.toka.app.data.firebase.toIso
import com.toka.app.data.firebase.toTask
import com.toka.app.data.firebase.toTemplate
import com.toka.app.data.firebase.toTimestamp
import com.toka.app.data.descendantIds
import com.toka.app.data.isoToLocalDate
import com.toka.app.data.model.CompleteTaskResponse
import com.toka.app.data.model.CreateTemplateRequest
import com.toka.app.data.model.PersonDTO
import com.toka.app.data.model.TaskDTO
import com.toka.app.data.model.TemplateDTO
import com.toka.app.data.model.UpdateTemplateRequest
import com.toka.app.data.suspendCatching
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tareas y plantillas sobre Firestore. Las lecturas son listeners en tiempo real (Flow) que sirven
 * primero la caché local; las escrituras se aplican a la caché al instante y Firestore las sube
 * cuando hay red, así que NINGUNA escritura espera a la red (no se hace `await` del commit).
 *
 * La recurrencia vive aquí, en el cliente: al resolver una tarea recurrente, el mismo lote crea la
 * siguiente con un id determinista ([nextTaskId]). Dos teléfonos que completan la misma tarea sin
 * conexión escriben el mismo documento y no se duplica.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepository(
    private val db: FirebaseFirestore,
    private val session: SessionRepository,
    private val household: HouseholdRepository,
    private val cache: SessionCache
) {
    private val householdId: Flow<String?> = session.session
        .map { (it as? Session.InHousehold)?.householdId }
        .distinctUntilChanged()

    private val peopleById: Flow<Map<String, PersonDTO>> = household.people.map { list -> list.associateBy { it.id } }

    // ── Lecturas reactivas ────────────────────────────────────────────────────

    /** Pendientes, la más próxima (o más atrasada) primero. */
    val pendingTasks: Flow<List<TaskDTO>> = householdId.flatMapLatest { hid ->
        if (hid == null) flowOf(emptyList())
        else combine(
            db.tasks(hid).whereEqualTo("status", "pending").asFlow(metadata = true),
            peopleById
        ) { snap, people -> snap.documents.map { it.toTask(people) }.sortedBy { it.dueAt } }
            .retryOnPermissionDenied()
            .catch { Log.w(TAG, "pendientes", it); emit(emptyList()) }
    }

    /** Resueltas del último año, la más reciente primero. La UI filtra por la ventana que elija. */
    val history: Flow<List<TaskDTO>> = householdId.flatMapLatest { hid ->
        if (hid == null) flowOf(emptyList())
        else {
            val cutoff = Timestamp(Timestamp.now().seconds - HISTORY_DAYS * 86_400L, 0)
            combine(
                db.tasks(hid).whereGreaterThanOrEqualTo("completedAt", cutoff)
                    .orderBy("completedAt", Query.Direction.DESCENDING).asFlow(metadata = true),
                peopleById
            ) { snap, people -> snap.documents.map { it.toTask(people) } }
                .retryOnPermissionDenied()
                .catch { Log.w(TAG, "historial", it); emit(emptyList()) }
        }
    }

    val templates: Flow<List<TemplateDTO>> = householdId.flatMapLatest { hid ->
        if (hid == null) flowOf(emptyList())
        else db.templates(hid).whereEqualTo("isActive", true).asFlow()
            .map { snap -> snap.documents.map { it.toTemplate() }.sortedBy { it.name.lowercase() } }
            .retryOnPermissionDenied()
            .catch { Log.w(TAG, "plantillas", it); emit(emptyList()) }
    }

    /** Cuántas escrituras locales esperan confirmación del servidor (indicador "sin sincronizar"). */
    val pendingSyncCount: Flow<Int> = combine(pendingTasks, history) { a, b ->
        a.count { it.pendingSync } + b.count { it.pendingSync }
    }

    fun taskFlow(taskId: String): Flow<TaskDTO?> = householdId.flatMapLatest { hid ->
        if (hid == null) flowOf(null)
        else combine(db.tasks(hid).document(taskId).asFlow(metadata = true), peopleById) { snap, people ->
            if (snap.exists()) snap.toTask(people) else null
        }.retryOnPermissionDenied().catch { emit(null) }
    }

    // ── Escrituras ────────────────────────────────────────────────────────────

    suspend fun completeTask(taskId: String, notes: String? = null): Result<CompleteTaskResponse> =
        resolve(taskId, "done", notes)

    suspend fun skipTask(taskId: String): Result<CompleteTaskResponse> = resolve(taskId, "skipped", null)

    private suspend fun resolve(taskId: String, newStatus: String, notes: String?): Result<CompleteTaskResponse> =
        suspendCatching {
            val (uid, hid) = identity()
            val taskRef = db.tasks(hid).document(taskId)
            val task = fetch(taskRef) ?: error("task $taskId not found")
            check(task.getString("status") == "pending") { "task $taskId already resolved" }
            val template = task.getString("templateId")?.let { fetch(db.templates(hid).document(it)) }

            val now = Timestamp.now()
            val batch = db.batch()
            val changes = mutableMapOf<String, Any?>(
                "status" to newStatus,
                "completedById" to uid,
                "completedAt" to now,
                "updatedAt" to now
            )
            if (notes != null) changes["notes"] = notes
            batch.update(taskRef, changes)

            // Recurrencia: la próxima vence desde el momento de completar, no desde el vencimiento original.
            var nextDue: Timestamp? = null
            val days = template?.getLong("recurrenceDays")?.toInt()
            if (template != null && days != null && template.getBoolean("isActive") != false) {
                nextDue = Timestamp(now.seconds + days * 86_400L, now.nanoseconds)
                batch.set(
                    db.tasks(hid).document(nextTaskId(taskId)),
                    taskData(
                        templateId = template.id,
                        templateName = template.getString("name"),
                        dueAt = nextDue,
                        assignedToId = template.getString("preferredAssigneeId"),
                        uid = uid,
                        now = now
                    ) + ("generatedFrom" to taskId)
                )
            }
            // Flujos: completar (no saltar) dispara las plantillas encadenadas a esta, con id determinista.
            if (newStatus == "done" && template != null) {
                followers(hid, template.id).forEach { f ->
                    val delay = f.getLong("triggerDelayDays") ?: 0L
                    batch.set(
                        db.tasks(hid).document(followUpTaskId(taskId, f.id)),
                        taskData(
                            templateId = f.id,
                            templateName = f.getString("name"),
                            dueAt = Timestamp(now.seconds + delay * 86_400L, now.nanoseconds),
                            assignedToId = f.getString("preferredAssigneeId"),
                            uid = uid,
                            now = now
                        ) + ("generatedFrom" to taskId)
                    )
                }
            }
            send(batch.commit(), "resolver $taskId")
            CompleteTaskResponse(status = newStatus, nextDueAt = nextDue?.toIso())
        }

    /**
     * Deshace un completado/saltado: la tarea vuelve a pendiente y, si la recurrencia había generado
     * la siguiente y sigue pendiente, se borra.
     */
    suspend fun undoTask(taskId: String): Result<Unit> = suspendCatching {
        val (_, hid) = identity()
        val taskRef = db.tasks(hid).document(taskId)
        val nextRef = db.tasks(hid).document(nextTaskId(taskId))
        val next = fetch(nextRef)
        val now = Timestamp.now()
        val batch = db.batch()
        batch.update(taskRef, mapOf("status" to "pending", "completedById" to null, "completedAt" to null, "updatedAt" to now))
        if (next != null && next.getString("status") == "pending") batch.delete(nextRef)
        // También las tareas encadenadas que disparó, mientras sigan pendientes.
        val templateId = fetch(taskRef)?.getString("templateId")
        if (templateId != null) {
            followers(hid, templateId).forEach { f ->
                val ref = db.tasks(hid).document(followUpTaskId(taskId, f.id))
                if (fetch(ref)?.getString("status") == "pending") batch.delete(ref)
            }
        }
        send(batch.commit(), "deshacer $taskId")
    }

    /** Campos en null = no tocar. */
    suspend fun updateTask(
        taskId: String,
        assignedToId: String? = null,
        notes: String? = null,
        dueAt: String? = null
    ): Result<Unit> = suspendCatching {
        val (_, hid) = identity()
        val changes = mutableMapOf<String, Any?>("updatedAt" to Timestamp.now())
        if (assignedToId != null) changes["assignedToId"] = assignedToId
        if (notes != null) changes["notes"] = notes
        if (dueAt != null) changes["dueAt"] = dueAt.toTimestamp()
        send(db.tasks(hid).document(taskId).update(changes), "editar $taskId")
    }

    suspend fun createTemplate(request: CreateTemplateRequest): Result<TemplateDTO> = suspendCatching {
        val (uid, hid) = identity()
        val now = Timestamp.now()
        val templateRef = db.templates(hid).document()
        val days = request.recurrenceDays
        val preferred = request.preferredAssigneeId

        val batch = db.batch()
        batch.set(
            templateRef,
            mapOf(
                "name" to request.name.trim(),
                "description" to request.description?.takeIf { it.isNotBlank() },
                "recurrenceDays" to days,
                "preferredAssigneeId" to preferred,
                "reminderTimes" to request.reminderTimes?.takeIf { it.isNotBlank() },
                "triggerTemplateId" to request.triggerTemplateId,
                "triggerDelayDays" to request.triggerTemplateId?.let { request.triggerDelayDays ?: 0 },
                "isActive" to true,
                "createdBy" to uid,
                "createdAt" to now,
                "updatedAt" to now
            )
        )
        // La primera instancia vence en la recurrencia (o en 7 días si es de una sola vez). Una plantilla
        // encadenada no la tiene: nace cuando se completa la tarea que la dispara.
        if (request.triggerTemplateId == null) {
            val due = Timestamp(now.seconds + (days ?: DEFAULT_DUE_DAYS) * 86_400L, now.nanoseconds)
            batch.set(
                db.tasks(hid).document(),
                taskData(templateRef.id, request.name.trim(), due, preferred, uid, now)
            )
        }
        send(batch.commit(), "crear plantilla")
        TemplateDTO(
            id = templateRef.id,
            name = request.name.trim(),
            description = request.description,
            recurrenceDays = days,
            preferredAssigneeId = preferred,
            reminderTimes = request.reminderTimes,
            isActive = true,
            triggerTemplateId = request.triggerTemplateId,
            triggerDelayDays = request.triggerTemplateId?.let { request.triggerDelayDays ?: 0 }
        )
    }

    suspend fun updateTemplate(id: String, request: UpdateTemplateRequest): Result<Unit> = suspendCatching {
        val (_, hid) = identity()
        val templateRef = db.templates(hid).document(id)
        val changes = mutableMapOf<String, Any?>("updatedAt" to Timestamp.now())
        request.name?.let { changes["name"] = it.trim() }
        request.description?.let { changes["description"] = it.ifBlank { null } }
        request.recurrenceDays?.let { changes["recurrenceDays"] = it }
        request.preferredAssigneeId?.let { changes["preferredAssigneeId"] = it }
        request.reminderTimes?.let { changes["reminderTimes"] = it.ifBlank { null } }
        request.isActive?.let { changes["isActive"] = it }

        val batch = db.batch()
        batch.update(templateRef, changes)
        if (request.isActive == false) retirePending(batch, hid, id)
        // Si cambia el nombre, las instancias pendientes siguen mostrando el viejo hasta resolverse:
        // se refrescan para que la lista no quede inconsistente.
        request.name?.let { newName -> renamePending(batch, hid, id, newName.trim()) }
        send(batch.commit(), "editar plantilla $id")
    }

    /**
     * Cambia la recurrencia, incluida la vuelta a "una sola vez" (null). Las instancias ya creadas no
     * cambian; vale desde la próxima que se genere.
     */
    suspend fun setTemplateRecurrence(templateId: String, recurrenceDays: Int?): Result<Unit> = suspendCatching {
        val (_, hid) = identity()
        send(
            db.templates(hid).document(templateId)
                .update(mapOf("recurrenceDays" to recurrenceDays?.takeIf { it > 0 }, "updatedAt" to Timestamp.now())),
            "recurrencia $templateId"
        )
    }

    /**
     * Baja la plantilla (no se borra) y retira sus tareas pendientes; el historial se conserva. Las
     * plantillas que cuelgan de ella en un flujo quedarían sin forma de nacer, así que se dan de baja también.
     */
    suspend fun deleteTemplate(id: String): Result<Unit> = suspendCatching {
        val (_, hid) = identity()
        val chained = descendantIds(id, activeTemplates(hid).map { it.toTemplate() })
        val batch = db.batch()
        (listOf(id) + chained).forEach { tid ->
            batch.update(db.templates(hid).document(tid), mapOf("isActive" to false, "updatedAt" to Timestamp.now()))
            retirePending(batch, hid, tid)
        }
        send(batch.commit(), "borrar plantilla $id")
    }

    /**
     * Pone o quita el disparador de una plantilla (null = se agenda sola). Una plantilla encadenada es de una
     * sola vez. Al quitarle el disparador, si no le queda ninguna tarea pendiente se crea una, o no volvería a aparecer.
     */
    suspend fun setTemplateTrigger(
        templateId: String,
        triggerTemplateId: String?,
        delayDays: Int?,
        recurrenceDays: Int?
    ): Result<Unit> = suspendCatching {
        val (uid, hid) = identity()
        val ref = db.templates(hid).document(templateId)
        val now = Timestamp.now()
        val batch = db.batch()
        val changes = mutableMapOf<String, Any?>(
            "triggerTemplateId" to triggerTemplateId,
            "triggerDelayDays" to triggerTemplateId?.let { delayDays ?: 0 },
            "updatedAt" to now
        )
        if (triggerTemplateId != null) changes["recurrenceDays"] = null
        batch.update(ref, changes)
        if (triggerTemplateId == null) {
            val template = fetch(ref)
            val hasPending = !queryOnce(
                db.tasks(hid).whereEqualTo("templateId", templateId).whereEqualTo("status", "pending")
            ).isEmpty
            if (template != null && !hasPending) {
                val due = Timestamp(now.seconds + (recurrenceDays ?: DEFAULT_DUE_DAYS) * 86_400L, now.nanoseconds)
                batch.set(
                    db.tasks(hid).document(),
                    taskData(templateId, template.getString("name"), due, template.getString("preferredAssigneeId"), uid, now)
                )
            }
        }
        send(batch.commit(), "disparador $templateId")
    }

    /** Todo es en vivo: no hay nada que "bajar". Se conserva por compatibilidad con el gesto de recargar. */
    suspend fun refresh(): Result<Unit> = Result.success(Unit)

    // ── Lecturas puntuales (workers, widget, acciones de notificación) ────────

    /**
     * Pendientes que vencen hoy (o ya vencieron) y cuya plantilla tiene recordatorios. Lee de la caché
     * local: sirve sin red y sin que haya un listener activo. Las horas son hora local del teléfono.
     */
    suspend fun reminderCandidates(): List<ReminderCandidate> = suspendCatching {
        val hid = cache.householdId ?: return@suspendCatching emptyList()
        val templates = queryOnce(db.templates(hid).whereEqualTo("isActive", true))
            .documents.associateBy { it.id }
        val today = LocalDate.now()
        queryOnce(db.tasks(hid).whereEqualTo("status", "pending")).documents.mapNotNull { task ->
            val times = task.getString("templateId")
                ?.let { templates[it]?.getString("reminderTimes") }
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: return@mapNotNull null
            if (times.isEmpty()) return@mapNotNull null
            val due = isoToLocalDate(task.getTimestamp("dueAt")?.toIso()) ?: return@mapNotNull null
            // Avisa lo que vence hoy o ya está atrasado: una tarea de ayer es justo la que más conviene recordar.
            if (due.isAfter(today)) return@mapNotNull null
            ReminderCandidate(task.id, task.getString("templateName") ?: "Tarea pendiente", times)
        }
    }.getOrDefault(emptyList())

    /**
     * Para el widget "Mis tareas de hoy": pendientes de hoy o atrasadas (por fecha LOCAL), de esta persona
     * o sin asignar, las más viejas primero.
     */
    suspend fun todayTasks(): List<WidgetTask> = suspendCatching {
        val hid = cache.householdId ?: return@suspendCatching emptyList()
        val uid = cache.uid
        val today = LocalDate.now()
        queryOnce(db.tasks(hid).whereEqualTo("status", "pending")).documents
            .filter { val a = it.getString("assignedToId"); a == null || a == uid }
            .filter { isoToLocalDate(it.getTimestamp("dueAt")?.toIso())?.let { d -> !d.isAfter(today) } ?: false }
            .sortedBy { it.getTimestamp("dueAt") }
            .map { WidgetTask(it.id, it.getString("templateName") ?: "Tarea pendiente") }
    }.getOrDefault(emptyList())

    // ── Internos ──────────────────────────────────────────────────────────────

    private fun identity(): Pair<String, String> {
        val uid = cache.uid ?: error("Sin sesión")
        val hid = cache.householdId ?: error("Sin hogar")
        return uid to hid
    }

    /** Lee un documento de la caché (que está al día mientras haya un listener) y, si no está, del servidor. */
    private suspend fun fetch(ref: DocumentReference): DocumentSnapshot? {
        val cached = try {
            ref.get(Source.CACHE).await().takeIf { it.exists() }
        } catch (_: FirebaseFirestoreException) {
            null // no está en la caché
        }
        if (cached != null) return cached
        return withTimeoutOrNull(FETCH_TIMEOUT_MS) { runCatching { ref.get().await() }.getOrNull() }?.takeIf { it.exists() }
    }

    private suspend fun queryOnce(query: Query): QuerySnapshot =
        withTimeoutOrNull(FETCH_TIMEOUT_MS) { runCatching { query.get().await() }.getOrNull() }
            ?: query.get(Source.CACHE).await()

    private suspend fun retirePending(batch: com.google.firebase.firestore.WriteBatch, hid: String, templateId: String) {
        val pending = queryOnce(db.tasks(hid).whereEqualTo("templateId", templateId).whereEqualTo("status", "pending"))
        pending.documents.forEach { batch.delete(it.reference) }
    }

    private suspend fun renamePending(batch: com.google.firebase.firestore.WriteBatch, hid: String, templateId: String, name: String) {
        val pending = queryOnce(db.tasks(hid).whereEqualTo("templateId", templateId).whereEqualTo("status", "pending"))
        pending.documents.forEach { batch.update(it.reference, mapOf("templateName" to name, "updatedAt" to Timestamp.now())) }
    }

    /** Plantillas activas del hogar. Cache primero: completar o borrar no debe esperar a la red. */
    private suspend fun activeTemplates(hid: String): List<DocumentSnapshot> {
        val query = db.templates(hid).whereEqualTo("isActive", true)
        val snap = try {
            query.get(Source.CACHE).await()
        } catch (_: FirebaseFirestoreException) {
            queryOnce(query)
        }
        return snap.documents
    }

    /** Plantillas activas encadenadas directamente a [templateId]. */
    private suspend fun followers(hid: String, templateId: String): List<DocumentSnapshot> =
        activeTemplates(hid).filter { it.getString("triggerTemplateId") == templateId }

    private fun taskData(
        templateId: String,
        templateName: String?,
        dueAt: Timestamp,
        assignedToId: String?,
        uid: String,
        now: Timestamp
    ): Map<String, Any?> = mapOf(
        "templateId" to templateId,
        "templateName" to templateName,
        "status" to "pending",
        "dueAt" to dueAt,
        "assignedToId" to assignedToId,
        "createdBy" to uid,
        "createdAt" to now,
        "updatedAt" to now
    )

    /** No se espera la confirmación del servidor (sin red tardaría para siempre); solo se registra si falla. */
    private fun send(task: com.google.android.gms.tasks.Task<Void>, what: String) {
        task.addOnFailureListener { Log.w(TAG, "Firestore rechazó '$what'", it) }
    }

    private companion object {
        const val TAG = "TaskRepository"
        const val HISTORY_DAYS = 365
        const val DEFAULT_DUE_DAYS = 7
        const val FETCH_TIMEOUT_MS = 4_000L
    }
}

data class ReminderCandidate(
    val taskId: String,
    val name: String,
    val times: List<String>
)

data class WidgetTask(val id: String, val name: String)
