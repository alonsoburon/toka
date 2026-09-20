package com.toka.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncPullResponse(
    val cursor: Long = 0,
    val people: List<PersonDTO> = emptyList(),
    val templates: List<TemplateDTO> = emptyList(),
    val tasks: List<TaskDTO> = emptyList(),
    val deleted: List<TombstoneDTO> = emptyList()
)

@Serializable
data class TombstoneDTO(
    val entity: String = "",
    val id: Long = 0,
    @SerialName("row_version") val rowVersion: Long = 0
)

@Serializable
data class MutationEnvelope(
    @SerialName("mutation_id") val mutationId: String,
    val op: String,
    val payload: JsonElement
)

@Serializable
data class PushRequest(
    val mutations: List<MutationEnvelope>
)

@Serializable
data class MutationResultDTO(
    @SerialName("mutation_id") val mutationId: String = "",
    val status: Int = 0,
    val body: JsonObject? = null,
    val duplicate: Boolean = false
)

@Serializable
data class PushResponse(
    val results: List<MutationResultDTO> = emptyList(),
    val cursor: Long = 0
)
