package com.toka.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SQLite es la fuente de verdad de la interfaz.
 *
 * Las pantallas nunca esperan a la red: leen de estas tablas y se redibujan cuando el
 * sincronizador las actualiza. Sin conexión la app funciona igual, solo que con datos
 * más viejos y con la cola de escrituras acumulándose.
 *
 * Los campos espejan los del servidor, incluido `rowVersion`, que es lo que permite
 * pedir "solo lo que cambió" en vez de descargar todo cada vez.
 */

@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey val id: Long,
    val householdId: Long,
    val name: String,
    val color: String,
    val avatarEmoji: String,
    val rowVersion: Long
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: Long,
    val householdId: Long,
    val name: String,
    val description: String?,
    val recurrenceDays: Int?,
    val preferredAssigneeId: Long?,
    /** Horas de recordatorio "HH:MM,HH:MM" (hora local del teléfono), o null si no avisa. */
    val reminderTimes: String? = null,
    val isActive: Boolean,
    val rowVersion: Long,
    /** El id que generó este dispositivo si la plantilla se creó sin conexión. */
    val clientId: String? = null,
    /** true mientras la creación siga en la cola y el servidor no la haya confirmado. */
    val pending: Boolean = false
)

@Entity(
    tableName = "tasks",
    indices = [Index("status"), Index("dueAt")]
)
data class TaskEntity(
    @PrimaryKey val id: Long,
    val templateId: Long?,
    val householdId: Long,
    val status: String,
    val dueAt: String?,
    val assignedToId: Long?,
    val completedById: Long?,
    val completedAt: String?,
    val notes: String?,
    val templateName: String?,
    val rowVersion: Long,
    val clientId: String? = null,
    /**
     * Marca que hay una escritura local sin confirmar sobre esta fila. La UI la usa
     * para mostrar el estado "por sincronizar" en vez de mentir diciendo que ya está.
     */
    val pending: Boolean = false
)

/**
 * La cola de escrituras.
 *
 * Cada entrada nace con un `mutationId` que no cambia nunca, ni siquiera entre
 * reintentos. Esa estabilidad es toda la deduplicación: el servidor usa ese id como
 * clave primaria, así que reenviar la misma mutación después de un timeout devuelve
 * el resultado original en vez de aplicarla dos veces.
 *
 * Un cliente no puede distinguir "no llegó" de "llegó y se perdió la respuesta", así
 * que la única estrategia segura es reenviar siempre y que el servidor decida.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val mutationId: String,
    val op: String,
    val payloadJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
    /** Último error, para poder mostrar por qué algo lleva rato sin subir. */
    val lastError: String? = null
)

/** Una sola fila: hasta dónde leyó este dispositivo. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val cursor: Long = 0,
    val lastSyncAt: Long? = null
)
