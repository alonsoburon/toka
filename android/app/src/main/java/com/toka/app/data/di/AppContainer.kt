package com.toka.app.data.di

import android.app.Application
import com.toka.app.data.SessionCache
import com.toka.app.data.firebase.FirebaseSetup
import com.toka.app.data.repository.AuthRepository
import com.toka.app.data.repository.HouseholdRepository
import com.toka.app.data.repository.SessionRepository
import com.toka.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Cableado manual (sin Hilt): una instancia de cada repositorio, creada al arrancar la app. */
class AppContainer private constructor(application: Application) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sessionCache = SessionCache(application)
    val authRepository = AuthRepository(FirebaseSetup.auth)
    val sessionRepository = SessionRepository(authRepository, FirebaseSetup.firestore, sessionCache, appScope)
    val householdRepository = HouseholdRepository(FirebaseSetup.firestore, sessionRepository, appScope)
    val taskRepository = TaskRepository(FirebaseSetup.firestore, sessionRepository, householdRepository, sessionCache)

    companion object {
        lateinit var instance: AppContainer
            private set

        fun init(application: Application) {
            instance = AppContainer(application)
        }
    }
}
