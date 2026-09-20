package com.toka.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String = ""
)

@Serializable
data class MeResponse(
    val person: PersonDTO,
    val household: HouseholdDTO
)

@Serializable
data class CreateHouseholdRequest(
    val name: String,
    @SerialName("admin_name")
    val adminName: String,
    @SerialName("admin_color")
    val adminColor: String,
    @SerialName("admin_emoji")
    val adminEmoji: String
)

@Serializable
data class CreateHouseholdResponse(
    val id: Long = 0,
    val name: String = "",
    @SerialName("invite_code")
    val inviteCode: String = "",
    @SerialName("person_id")
    val personId: Long = 0,
    val token: String = ""
)

@Serializable
data class HouseholdDTO(
    val id: Long = 0,
    val name: String = "",
    @SerialName("invite_code")
    val inviteCode: String? = null
)

@Serializable
data class PersonDTO(
    val id: Long = 0,
    @SerialName("household_id")
    val householdId: Long = 0,
    val name: String = "",
    val color: String = "",
    @SerialName("avatar_emoji")
    val avatarEmoji: String = "",
    val token: String = "",
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("created_by")
    val createdBy: Long? = null,
    @SerialName("updated_by")
    val updatedBy: Long? = null,
    @SerialName("row_version")
    val rowVersion: Long = 0,
    @SerialName("client_id")
    val clientId: String? = null
)

@Serializable
data class TemplateDTO(
    val id: Long = 0,
    @SerialName("household_id")
    val householdId: Long = 0,
    val name: String = "",
    val description: String? = null,
    @SerialName("recurrence_days")
    val recurrenceDays: Int? = null,
    @SerialName("preferred_assignee_id")
    val preferredAssigneeId: Long? = null,
    @SerialName("reminder_times")
    val reminderTimes: String? = null,
    @SerialName("is_active")
    val isActive: Boolean = true,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("created_by")
    val createdBy: Long? = null,
    @SerialName("updated_by")
    val updatedBy: Long? = null,
    @SerialName("row_version")
    val rowVersion: Long = 0,
    @SerialName("client_id")
    val clientId: String? = null
)

@Serializable
data class TaskDTO(
    val id: Long = 0,
    @SerialName("template_id")
    val templateId: Long? = null,
    @SerialName("household_id")
    val householdId: Long = 0,
    val status: String = "",
    @SerialName("due_at")
    val dueAt: String? = null,
    @SerialName("assigned_to_id")
    val assignedToId: Long? = null,
    @SerialName("completed_by_id")
    val completedById: Long? = null,
    @SerialName("completed_at")
    val completedAt: String? = null,
    val notes: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("created_by")
    val createdBy: Long? = null,
    @SerialName("updated_by")
    val updatedBy: Long? = null,
    @SerialName("template_name")
    val templateName: String? = null,
    @SerialName("assigned_to_name")
    val assignedToName: String? = null,
    @SerialName("assigned_to_color")
    val assignedToColor: String? = null,
    @SerialName("assigned_to_emoji")
    val assignedToEmoji: String? = null,
    @SerialName("completed_by_name")
    val completedByName: String? = null,
    @SerialName("completed_by_color")
    val completedByColor: String? = null,
    @SerialName("completed_by_emoji")
    val completedByEmoji: String? = null,
    @SerialName("row_version")
    val rowVersion: Long = 0,
    @SerialName("client_id")
    val clientId: String? = null
)

@Serializable
data class MagicInvite(
    val server: String = "",
    val code: String = "",
    val household: String = ""
)

@Serializable
data class JoinHouseholdRequest(
    @SerialName("invite_code")
    val inviteCode: String,
    val name: String,
    val color: String,
    val emoji: String
)

@Serializable
data class JoinHouseholdResponse(
    @SerialName("person_id")
    val personId: Long = 0,
    @SerialName("household_id")
    val householdId: Long = 0,
    val token: String = "",
    val name: String = ""
)

@Serializable
data class CreatePersonRequest(
    val name: String,
    val color: String,
    val emoji: String
)

@Serializable
data class CreatePersonResponse(
    val id: Long = 0,
    val name: String = "",
    val color: String = "",
    val emoji: String = "",
    val token: String = ""
)

@Serializable
data class UpdatePersonRequest(
    val name: String? = null,
    val color: String? = null,
    @SerialName("avatar_emoji")
    val avatarEmoji: String? = null
)

@Serializable
data class CreateTemplateRequest(
    val name: String,
    val description: String? = null,
    @SerialName("recurrence_days")
    val recurrenceDays: Int? = null,
    @SerialName("preferred_assignee_id")
    val preferredAssigneeId: Long? = null,
    @SerialName("reminder_times")
    val reminderTimes: String? = null
)

@Serializable
data class SetRecurrenceRequest(
    @SerialName("recurrence_days")
    val recurrenceDays: Int? = null
)

@Serializable
data class UpdateTemplateRequest(
    val name: String? = null,
    val description: String? = null,
    @SerialName("recurrence_days")
    val recurrenceDays: Int? = null,
    @SerialName("preferred_assignee_id")
    val preferredAssigneeId: Long? = null,
    @SerialName("reminder_times")
    val reminderTimes: String? = null,
    @SerialName("is_active")
    val isActive: Boolean? = null
)

@Serializable
data class CompleteTaskRequest(
    val notes: String? = null
)

@Serializable
data class CompleteTaskResponse(
    val status: String = "",
    @SerialName("next_due_at")
    val nextDueAt: String? = null
)

@Serializable
data class UpdateTaskRequest(
    @SerialName("assigned_to_id")
    val assignedToId: Long? = null,
    @SerialName("due_at")
    val dueAt: String? = null,
    val notes: String? = null
)

@Serializable
data class GenericResponse(
    val status: String = ""
)

@Serializable
data class InviteRegenResponse(
    @SerialName("invite_code") val inviteCode: String
)
