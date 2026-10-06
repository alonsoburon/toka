package com.toka.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.toka.app.data.di.AppContainer
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Revisa cada ~15 min si hay tareas que vencen hoy con recordatorios ya vencidos y
 * avisa. Lee de Room, así que no necesita red. WorkManager tiene un mínimo de 15 min,
 * de modo que un aviso de las 09:00 puede salir hasta 09:14.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Sin sesión (cerrada a mano o por un 401) Room todavía guarda las tareas del
        // hogar anterior: no hay que avisar de ellas.
        // El widget también se redibuja aquí: es lo que lo hace cambiar de día a medianoche.
        com.toka.app.widget.TodayWidgetProvider.refresh(applicationContext)
        if (AppContainer.instance.tokenStore.tokenFlow.first() == null) return Result.success()
        val candidates = AppContainer.instance.taskRepository.reminderCandidates()
        Notifications.maybeNotify(applicationContext, candidates)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "toka-reminders-periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
