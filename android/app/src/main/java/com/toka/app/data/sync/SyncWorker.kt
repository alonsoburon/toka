package com.toka.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.toka.app.data.di.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Sincroniza en segundo plano.
 *
 * WorkManager y no una corrutina suelta porque el trabajo tiene que sobrevivir a que
 * la pantalla se cierre y al proceso: una tarea que alguien marcó en el ascensor
 * tiene que subir cuando vuelva la señal, aunque para entonces la app esté cerrada.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = AppContainer.instance.syncEngine
        return engine.sync().fold(
            onSuccess = { Result.success() },
            onFailure = {
                // retry() reprograma con el backoff exponencial configurado abajo.
                // La cola no se pierde: sigue en SQLite con sus mutation_id intactos.
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        )
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val PERIODIC = "toka-sync-periodic"
        private const val ONE_SHOT = "toka-sync-now"

        private val networkRequired = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Red de seguridad: aunque nadie abra la app, la cola sube. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Se llama después de cada escritura local y al abrir la app.
         *
         * APPEND_OR_REPLACE y no REPLACE: si ya hay una sincronización corriendo, esta
         * se encola detrás en vez de cancelarla a mitad de camino.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
