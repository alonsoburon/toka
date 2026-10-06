package com.toka.app

import android.app.Application
import com.toka.app.data.di.AppContainer
import com.toka.app.notifications.ReminderWorker

class TokaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)

        // No hay cola de sincronización propia: Firestore guarda las escrituras sin conexión y las sube
        // solo cuando hay red, también con la app cerrada mientras el proceso viva.

        // Recordatorios locales de tareas que vencen hoy.
        ReminderWorker.schedule(this)
    }
}
