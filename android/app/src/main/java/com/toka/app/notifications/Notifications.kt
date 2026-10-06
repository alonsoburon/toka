package com.toka.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.toka.app.MainActivity
import com.toka.app.R
import com.toka.app.data.repository.ReminderCandidate
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Notificaciones locales de recordatorio. No hay push remoto: el teléfono revisa sus
 * propias tareas pendientes que vencen hoy y avisa a las horas configuradas en la
 * plantilla. La marca de "ya avisado" se guarda por (día, tarea, hora) para no repetir.
 */
object Notifications {
    /** Extra que lleva el Intent para abrir la tarea concreta al tocar el aviso. */
    const val EXTRA_TASK_ID = "task_id"
    private const val CHANNEL_ID = "toka-reminders"
    private const val PREFS = "toka_reminders"
    private const val KEY = "notified"

    // "H:mm" y no "HH:mm": acepta tanto "9:00" como "09:00".
    private val timeFormat = DateTimeFormatter.ofPattern("H:mm")

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_description) }
        manager.createNotificationChannel(channel)
    }

    fun maybeNotify(context: Context, candidates: List<ReminderCandidate>) {
        ensureChannel(context)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY, emptySet())!!.toMutableSet()
        val today = LocalDate.now().toString()
        val now = LocalTime.now()

        var posted = false
        for (candidate in candidates) {
            // Solo la última hora ya pasada: si la tarea aparece "hoy" a las 18:00 con avisos
            // a las 09:00 y 12:00, es un solo aviso, no una ráfaga con todas las anteriores.
            val due = candidate.times
                .mapNotNull { raw ->
                    runCatching { LocalTime.parse(raw, timeFormat) }.getOrNull()?.let { raw to it }
                }
                .filter { (_, time) -> !now.isBefore(time) }
                .maxByOrNull { (_, time) -> time }
                ?: continue
            val raw = due.first

            val key = "$today-${candidate.taskId}-$raw"
            if (key in notified) continue

            // Si no se pudo mostrar (permiso denegado) no se marca como avisado: cuando
            // el usuario conceda el permiso, el aviso de hoy todavía puede salir.
            if (post(context, candidate, raw)) {
                notified.add(key)
                posted = true
            }
        }

        // Poda: solo conservamos las marcas de hoy, para que el set no crezca sin fin.
        notified.removeIf { !it.startsWith(today) }
        if (posted) {
            prefs.edit().putStringSet(KEY, notified).apply()
        }
    }

    /** Devuelve true si la notificación se publicó. */
    private fun post(context: Context, candidate: ReminderCandidate, time: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TASK_ID, candidate.taskId)
        }
        val id = (candidate.taskId.toString() + time).hashCode()
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completePending =
            actionPending(context, candidate.taskId, id, id, TaskActionWorker.ACTION_COMPLETE)
        val skipPending =
            actionPending(context, candidate.taskId, id + 1, id, TaskActionWorker.ACTION_SKIP)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(candidate.name)
            .setContentText(context.getString(R.string.notif_text, time))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(0, context.getString(R.string.notif_complete_action), completePending)
            .addAction(0, context.getString(R.string.notif_skip_action), skipPending)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) {
            // Falta el permiso POST_NOTIFICATIONS: no hay nada que hacer.
            false
        }
    }

    private fun actionPending(
        context: Context,
        taskId: String,
        requestCode: Int,
        notificationId: Int,
        action: String
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(NotificationActionReceiver.EXTRA_ACTION, action)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
