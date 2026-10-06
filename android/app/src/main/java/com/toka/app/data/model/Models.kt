package com.toka.app.data.model

/**
 * Modelos que ve la UI. Los ids son los de Firestore (String): el de una persona es su uid de
 * Firebase Auth. Las fechas viajan como instantes ISO-8601 en UTC (lo que ya entendía la UI).
 */
data class PersonDTO(
    val id: String = "",
    val name: String = "",
    val color: String = "#a78bfa",
    val avatarEmoji: String = "🐣"
)

data class HouseholdDTO(
    val id: String = "",
    val name: String = "",
    val inviteCode: String = "",
    val memberIds: List<String> = emptyList()
)

data class TemplateDTO(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val recurrenceDays: Int? = null,
    val preferredAssigneeId: String? = null,
    val reminderTimes: String? = null,
    val isActive: Boolean = true
)

data class TaskDTO(
    val id: String = "",
    val templateId: String? = null,
    val status: String = "",
    val dueAt: String? = null,
    val assignedToId: String? = null,
    val completedById: String? = null,
    val completedAt: String? = null,
    val notes: String? = null,
    val templateName: String? = null,
    val assignedToName: String? = null,
    val assignedToColor: String? = null,
    val assignedToEmoji: String? = null,
    val completedByName: String? = null,
    val completedByColor: String? = null,
    val completedByEmoji: String? = null,
    /** Hay una escritura local que Firestore todavía no confirmó con el servidor. */
    val pendingSync: Boolean = false
)

data class CreateTemplateRequest(
    val name: String,
    val description: String? = null,
    val recurrenceDays: Int? = null,
    val preferredAssigneeId: String? = null,
    val reminderTimes: String? = null
)

/** Campos en null = no tocar. */
data class UpdateTemplateRequest(
    val name: String? = null,
    val description: String? = null,
    val recurrenceDays: Int? = null,
    val preferredAssigneeId: String? = null,
    val reminderTimes: String? = null,
    val isActive: Boolean? = null
)

data class CompleteTaskResponse(
    val status: String,
    val nextDueAt: String? = null
)
