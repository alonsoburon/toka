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
    return if (times.isEmpty()) null else times.joinToString(",")
}
