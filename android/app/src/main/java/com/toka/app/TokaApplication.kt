package com.toka.app

import android.app.Application
import com.toka.app.data.di.AppContainer
import com.toka.app.data.sync.SyncWorker
import com.toka.app.notifications.ReminderWorker

class TokaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)

        // Un ciclo al abrir, para que lo que quedó en la cola de la sesión anterior
        // suba cuanto antes, y otro periódico como red de seguridad por si la app no
        // se abre en un rato.
        SyncWorker.syncNow(this)
        SyncWorker.schedulePeriodic(this)

        // Recordatorios locales de tareas que vencen hoy.
        ReminderWorker.schedule(this)
    }
}
