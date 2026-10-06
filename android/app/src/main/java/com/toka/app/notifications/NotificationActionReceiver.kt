package com.toka.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * Recibe el toque en los botones "Completar" / "Saltar" de la notificación. No hace el
 * trabajo él mismo: encola un [TaskActionWorker] y descarta la notificación.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(Notifications.EXTRA_TASK_ID)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        if (taskId.isNullOrBlank()) return

        // Expedited: el toque en el botón no debe quedar esperando a la ventana de Doze.
        val request = OneTimeWorkRequestBuilder<TaskActionWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                Data.Builder()
                    .putString(TaskActionWorker.KEY_TASK_ID, taskId)
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
