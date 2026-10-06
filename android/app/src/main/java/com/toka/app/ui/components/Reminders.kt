package com.toka.app.ui.components

/**
 * Normaliza "09:00, 20:00" a "09:00,20:00". Descarta lo que no sea HH:MM.
 * Devuelve null si no queda ninguna hora válida.
 */
fun normalizeReminders(raw: String): String? {
    val times = raw.split(Regex("[,\\s]+"))
        .map { it.trim() }
        .filter { Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(it) }
        .distinct()
    // "9:00" -> "09:00": un solo formato guardado.
    val padded = times.map { if (it.indexOf(':') == 1) "0$it" else it }.distinct()
    return if (padded.isEmpty()) null else padded.joinToString(",")
}
