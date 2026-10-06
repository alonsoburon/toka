package com.toka.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.toka.app.data.di.AppContainer

/**
 * Completa o salta una tarea desde una acción de la notificación. Corre en WorkManager
 * para que sobreviva a que el usuario cierre la pantalla, igual que SyncWorker.
 *
 * La escritura es local-first: aplica en Room y encola la mutación, con o sin red.
 */
class TaskActionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
        if (taskId.isNullOrBlank()) return Result.failure()

        val repository = AppContainer.instance.taskRepository
        val outcome = when (inputData.getString(KEY_ACTION)) {
            ACTION_COMPLETE -> repository.completeTask(taskId).map { }
            ACTION_SKIP -> repository.skipTask(taskId).map { }
            else -> return Result.failure()
        }
        // Si la tarea ya no existe localmente (otro teléfono la resolvió y el sync la
        // quitó) no hay nada que reintentar.
        return if (outcome.isSuccess) Result.success() else Result.failure()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_ACTION = "action"
        const val ACTION_COMPLETE = "complete"
        const val ACTION_SKIP = "skip"
    }
}
