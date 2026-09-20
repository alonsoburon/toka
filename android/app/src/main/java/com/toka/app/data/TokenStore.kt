package com.toka.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "toka_prefs")

class TokenStore(private val context: Context) {

    private val tokenKey = stringPreferencesKey("auth_token")
    private val nameKey = stringPreferencesKey("user_name")
    private val emojiKey = stringPreferencesKey("user_emoji")
    private val colorKey = stringPreferencesKey("user_color")
    private val personIdKey = stringPreferencesKey("person_id")
    private val householdIdKey = stringPreferencesKey("household_id")
    private val householdNameKey = stringPreferencesKey("household_name")
    private val inviteCodeKey = stringPreferencesKey("invite_code")
    private val serverUrlKey = stringPreferencesKey("server_url")

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[tokenKey] }
    val isLoggedIn: Flow<Boolean> = tokenFlow.map { it != null }
    val userName: Flow<String?> = context.dataStore.data.map { it[nameKey] }
    val userEmoji: Flow<String?> = context.dataStore.data.map { it[emojiKey] }
    val userColor: Flow<String?> = context.dataStore.data.map { it[colorKey] }
    val personId: Flow<Long?> = context.dataStore.data.map { it[personIdKey]?.toLongOrNull() }
    val householdId: Flow<String?> = context.dataStore.data.map { it[householdIdKey] }
    val householdName: Flow<String?> = context.dataStore.data.map { it[householdNameKey] }
    val inviteCode: Flow<String?> = context.dataStore.data.map { it[inviteCodeKey] }
    val serverUrl: Flow<String?> = context.dataStore.data.map { it[serverUrlKey] }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { it[tokenKey] = token }
    }

    suspend fun saveSession(
        token: String,
        name: String,
        emoji: String,
        color: String,
        personId: Long,
        householdId: String,
        householdName: String,
        inviteCode: String
    ) {
        context.dataStore.edit {
            it[tokenKey] = token
            it[nameKey] = name
            it[emojiKey] = emoji
            it[colorKey] = color
            it[personIdKey] = personId.toString()
            it[householdIdKey] = householdId
            it[householdNameKey] = householdName
            it[inviteCodeKey] = inviteCode
        }
    }

    suspend fun saveInviteCode(code: String) {
        context.dataStore.edit { it[inviteCodeKey] = code }
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { it[serverUrlKey] = url }
    }

    suspend fun clearSession() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun getServerUrl(): String? {
        return context.dataStore.data.first()[serverUrlKey]
    }

    suspend fun getHouseholdId(): Long? {
        return context.dataStore.data.first()[householdIdKey]?.toLongOrNull()
    }

    suspend fun getPersonId(): Long? {
        return context.dataStore.data.first()[personIdKey]?.toLongOrNull()
    }
}
