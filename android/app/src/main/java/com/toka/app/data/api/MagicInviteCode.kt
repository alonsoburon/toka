package com.toka.app.data.api

import android.util.Base64
import kotlinx.serialization.json.Json

/**
 * El "código mágico" que se comparte para entrar a un hogar: un MagicInvite
 * (servidor + código de invitación + nombre del hogar) serializado a JSON y
 * codificado en base64 URL-safe. Vive en un solo lugar para que quien lo emite
 * (PeopleScreen) y quien lo consume (JoinHouseholdScreen) no puedan divergir.
 */
private val magicJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun encodeMagicInvite(server: String, code: String, household: String): String {
    val payload = magicJson.encodeToString(
        MagicInvite.serializer(),
        MagicInvite(server = server, code = code, household = household)
    )
    return Base64.encodeToString(
        payload.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP
    )
}

/**
 * Decodifica el código base64 (URL-safe o estándar) a un MagicInvite. Devuelve null
 * si no es base64 válido o si le falta el servidor o el código.
 */
fun decodeMagicInvite(raw: String): MagicInvite? {
    val cleaned = raw.trim().filterNot { it.isWhitespace() }
    if (cleaned.isEmpty()) return null

    val decoded = sequenceOf(Base64.URL_SAFE, Base64.DEFAULT)
        .mapNotNull { flags ->
            runCatching {
                String(Base64.decode(cleaned, flags or Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()
        }
        .firstOrNull { it.isNotBlank() && it.trimStart().startsWith("{") }
        ?: return null

    return runCatching { magicJson.decodeFromString(MagicInvite.serializer(), decoded) }
        .getOrNull()
        ?.takeIf { it.server.isNotBlank() && it.code.isNotBlank() }
}
