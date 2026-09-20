package com.toka.app.data.repository

import com.toka.app.data.TokenStore
import com.toka.app.data.api.CreateHouseholdRequest
import com.toka.app.data.api.CreateHouseholdResponse
import com.toka.app.data.api.JoinHouseholdRequest
import com.toka.app.data.api.JoinHouseholdResponse
import com.toka.app.data.api.MeResponse
import com.toka.app.data.api.TokaApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class AuthRepository(
    private val api: () -> TokaApi,
    private val tokenStore: TokenStore
) {

    /**
     * Entra a un hogar existente con un invite code.
     */
    suspend fun joinHousehold(
        inviteCode: String,
        name: String,
        color: String,
        emoji: String,
        householdName: String
    ): Result<JoinHouseholdResponse> = runCatching {
        val response = api().joinHousehold(
            JoinHouseholdRequest(inviteCode, name, color, emoji)
        )
        tokenStore.saveSession(
            token = response.token,
            name = name,
            emoji = emoji,
            color = color,
            personId = response.personId,
            householdId = response.householdId.toString(),
            householdName = householdName,
            inviteCode = inviteCode
        )
        response
    }

    /**
     * Crea un hogar desde cero. La primera persona nace como admin y recibe su token
     * en la misma respuesta; a partir de ahí todo funciona igual que al unirse.
     */
    suspend fun createHousehold(
        householdName: String,
        name: String,
        color: String,
        emoji: String
    ): Result<CreateHouseholdResponse> = runCatching {
        val response = api().createHousehold(
            CreateHouseholdRequest(
                name = householdName,
                adminName = name,
                adminColor = color,
                adminEmoji = emoji
            )
        )
        tokenStore.saveSession(
            token = response.token,
            name = name,
            emoji = emoji,
            color = color,
            personId = response.personId,
            householdId = response.id.toString(),
            householdName = response.name,
            inviteCode = response.inviteCode
        )
        response
    }

    /**
     * Entra con un token ya emitido (por ejemplo el del seed). Es el camino que hace
     * persistente una identidad concreta: aunque se recree la base, el token del seed
     * sigue resolviendo a la misma persona.
     */
    suspend fun loginWithToken(token: String): Result<MeResponse> = runCatching {
        val me = api().me("Bearer $token")
        tokenStore.saveSession(
            token = token,
            name = me.person.name,
            emoji = me.person.avatarEmoji,
            color = me.person.color,
            personId = me.person.id,
            householdId = me.household.id.toString(),
            householdName = me.household.name,
            inviteCode = me.household.inviteCode ?: ""
        )
        me
    }

    /**
     * Borra la propia persona del hogar. A diferencia de cerrar sesión, el token deja
     * de existir en el servidor. Requiere conexión.
     */
    suspend fun leaveHousehold(): Result<Unit> = runCatching {
        val householdId = tokenStore.getHouseholdId() ?: error("No hay hogar activo")
        val token = tokenStore.tokenFlow.first() ?: error("Sin sesión")
        val response = api().leaveHousehold(householdId, "Bearer $token")
        if (!response.isSuccessful) {
            error("No se pudo salir del hogar (${response.code()})")
        }
        tokenStore.clearSession()
    }

    fun isLoggedIn(): Flow<Boolean> = tokenStore.isLoggedIn

    suspend fun logout() { tokenStore.clearSession() }
}
