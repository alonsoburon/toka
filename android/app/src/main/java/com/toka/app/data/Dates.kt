package com.toka.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fecha LOCAL de un instante ISO-8601 del servidor (siempre UTC). Partir el texto en la
 * "T" daba el día UTC: una tarea que vence a las 22:00 en Chile (UTC-3) decía "Mañana".
 */
fun isoToLocalDate(iso: String?): LocalDate? {
    if (iso == null) return null
    return try {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (_: Exception) {
        null
    }
}

/**
 * Vencimiento "ese día": el último minuto del día local, como instante ISO. Usar la
 * medianoche de inicio dejaba la tarea de "Hoy" atrasada en el mismo instante de crearla.
 */
fun endOfLocalDay(date: LocalDate): String =
    date.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toString()
