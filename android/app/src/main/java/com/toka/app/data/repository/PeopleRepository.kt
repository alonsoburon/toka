package com.toka.app.data.repository

import android.content.Context
import com.toka.app.data.TokenStore
import com.toka.app.data.api.CreatePersonRequest
import com.toka.app.data.api.CreatePersonResponse
import com.toka.app.data.api.PersonDTO
import com.toka.app.data.api.TokaApi
import com.toka.app.data.local.PersonEntity
import com.toka.app.data.local.TokaDao
import com.toka.app.data.sync.SyncEngine
import com.toka.app.data.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Las personas se leen de la copia local y se editan por la cola, igual que las
 * tareas.
 *
 * Dos operaciones siguen exigiendo conexión, y es a propósito:
 *
 *  - Añadir a alguien devuelve un token que solo el servidor puede emitir. Fingir que
 *    se creó y darle un token falso al usuario sería peor que decirle que espere.
 *  - Regenerar el invite code es lo mismo: el código nuevo lo decide el servidor.
 */
class PeopleRepository(
    private val api: () -> TokaApi,
    private val tokenStore: TokenStore,
    private val dao: TokaDao,
    private val sync: SyncEngine,
    private val appContext: Context
) {

    val people: Flow<List<PersonDTO>> = dao.people().map { list -> list.map { it.toDto() } }

    suspend fun getPeople(): Result<List<PersonDTO>> = runCatching {
        dao.peopleOnce().map { it.toDto() }
    }

    suspend fun updatePerson(
        id: Long,
        name: String? = null,
        color: String? = null,
        emoji: String? = null
    ): Result<PersonDTO> = runCatching {
        val row = dao.peopleOnce().firstOrNull { it.id == id } ?: error("person $id not found")
        val updated = row.copy(
            name = name ?: row.name,
            color = color ?: row.color,
            avatarEmoji = emoji ?: row.avatarEmoji
        )
        dao.upsertPeople(listOf(updated))

        sync.enqueue("person.update", buildJsonObject {
            put("id", JsonPrimitive(id))
            put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
            put("color", color?.let { JsonPrimitive(it) } ?: JsonNull)
            put("avatar_emoji", emoji?.let { JsonPrimitive(it) } ?: JsonNull)
        })
        SyncWorker.syncNow(appContext)

        updated.toDto()
    }

    /** Borra a una persona. Es offline: se aplica local y se encola la mutación. */
    suspend fun deletePerson(id: Long): Result<Unit> = runCatching {
        dao.deletePerson(id)
        sync.enqueue("person.delete", buildJsonObject {
            put("id", JsonPrimitive(id))
        })
        SyncWorker.syncNow(appContext)
    }

    /** Requiere conexión: el token de la persona nueva lo emite el servidor. */
    suspend fun addPerson(
        householdId: Long,
        name: String,
        color: String,
        emoji: String
    ): Result<CreatePersonResponse> = runCatching {
        val created = api().addPerson(
            householdId = householdId,
            request = CreatePersonRequest(name = name, color = color, emoji = emoji),
            token = getAuthHeader()
        )
        SyncWorker.syncNow(appContext)
        created
    }

    /** Requiere conexión: el código nuevo lo decide el servidor. */
    suspend fun regenerateInvite(householdId: Long): Result<String> = runCatching {
        api().regenerateInvite(householdId, getAuthHeader()).inviteCode
    }

    private suspend fun getAuthHeader(): String {
        val token = tokenStore.tokenFlow.first()
            ?: throw IllegalStateException("Not authenticated")
        return "Bearer $token"
    }
}

private fun PersonEntity.toDto() = PersonDTO(
    id = id,
    householdId = householdId,
    name = name,
    color = color,
    avatarEmoji = avatarEmoji,
    rowVersion = rowVersion
)
