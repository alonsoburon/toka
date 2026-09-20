package com.toka.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Recibe el toque en los botones "Completar" / "Saltar" de la notificación. No hace el
 * trabajo él mismo: encola un [TaskActionWorker] y descarta la notificación.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(Notifications.EXTRA_TASK_ID, -1L)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        if (taskId <= 0) return

        val request = OneTimeWorkRequestBuilder<TaskActionWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(TaskActionWorker.KEY_TASK_ID, taskId)
                    .putString(TaskActionWorker.KEY_ACTION, action)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
