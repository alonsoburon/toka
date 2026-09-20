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

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recordatorios",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Tareas que debes hacer hoy" }
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
            for (raw in candidate.times) {
                val time = runCatching { LocalTime.parse(raw, timeFormat) }.getOrNull() ?: continue
                if (now.isBefore(time)) continue

                val key = "$today-${candidate.taskId}-$raw"
                if (key in notified) continue

                post(context, candidate, raw)
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

    private fun post(context: Context, candidate: ReminderCandidate, time: String) {
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(candidate.name)
            .setContentText("Pendiente · aviso de las $time")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(0, "✓ Completar", completePending)
            .addAction(0, "Saltar", skipPending)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Falta el permiso POST_NOTIFICATIONS: no hay nada que hacer.
        }
    }

    private fun actionPending(
        context: Context,
        taskId: Long,
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
