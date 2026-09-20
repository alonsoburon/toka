package com.toka.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TokaDao {

    // ── Lecturas: son Flow, así que la UI se redibuja sola cuando el sync escribe ──

    @Query("SELECT * FROM tasks WHERE status = 'pending' ORDER BY (dueAt < :nowIso) DESC, dueAt ASC")
    fun pendingTasks(nowIso: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status IN ('done','skipped') ORDER BY completedAt DESC")
    fun history(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun task(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM templates WHERE isActive = 1 ORDER BY id")
    fun activeTemplates(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM people ORDER BY id")
    fun people(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM tasks WHERE status = 'pending' ORDER BY (dueAt < :nowIso) DESC, dueAt ASC")
    suspend fun pendingTasksOnce(nowIso: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status IN ('done','skipped') ORDER BY completedAt DESC")
    suspend fun historyOnce(): List<TaskEntity>

    @Query("SELECT * FROM templates WHERE isActive = 1 ORDER BY id")
    suspend fun activeTemplatesOnce(): List<TemplateEntity>

    @Query("SELECT * FROM people ORDER BY id")
    suspend fun peopleOnce(): List<PersonEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun taskOnce(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status = 'pending'")
    suspend fun allPendingOnce(): List<TaskEntity>

    // ── Escrituras locales ────────────────────────────────────────────────────
    //
    // REPLACE y no IGNORE: lo que llega del servidor siempre pisa a la copia local.
    // Con un contador de versiones global no hay ambigüedad sobre cuál es más nueva,
    // y el cliente nunca tiene una versión que el servidor no conozca — sus cambios
    // pasan por la cola, no se escriben directo.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeople(rows: List<PersonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplates(rows: List<TemplateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(rows: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(row: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(row: TemplateEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteTemplate(id: Long)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePerson(id: Long)

    // ── Cola de salida ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(row: OutboxEntity)

    /** En orden de creación: las mutaciones dependen unas de otras. */
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC LIMIT :limit")
    suspend fun outboxBatch(limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE mutationId = :id")
    suspend fun dequeue(id: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE mutationId = :id")
    suspend fun markAttempt(id: String, error: String?)

    // ── Cursor ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun syncState(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun syncStateFlow(): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncState(state: SyncStateEntity)

    @Transaction
    suspend fun applyPull(
        people: List<PersonEntity>,
        templates: List<TemplateEntity>,
        tasks: List<TaskEntity>,
        deleted: List<Pair<String, Long>>,
        cursor: Long
    ) {
        if (people.isNotEmpty()) upsertPeople(people)
        if (templates.isNotEmpty()) upsertTemplates(templates)
        if (tasks.isNotEmpty()) upsertTasks(tasks)
        for ((entity, id) in deleted) {
            when (entity) {
                "task" -> deleteTask(id)
                "template" -> deleteTemplate(id)
                "person" -> deletePerson(id)
            }
        }
        saveSyncState(SyncStateEntity(id = 1, cursor = cursor, lastSyncAt = System.currentTimeMillis()))
    }

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()

    @Query("DELETE FROM templates")
    suspend fun clearTemplates()

    @Query("DELETE FROM people")
    suspend fun clearPeople()

    @Query("DELETE FROM outbox")
    suspend fun clearOutbox()

    @Query("DELETE FROM sync_state")
    suspend fun clearSyncState()

    /** Al cerrar sesión no queda rastro del household anterior en el dispositivo. */
    @Transaction
    suspend fun clearAll() {
        clearTasks()
        clearTemplates()
        clearPeople()
        clearOutbox()
        clearSyncState()
    }
}
