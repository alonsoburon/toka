package com.toka.app.data.di

import android.app.Application
import com.toka.app.BuildConfig
import com.toka.app.data.TokenStore
import com.toka.app.data.api.TokaApi
import com.toka.app.data.repository.AuthRepository
import com.toka.app.data.repository.PeopleRepository
import com.toka.app.data.local.TokaDatabase
import com.toka.app.data.repository.TaskRepository
import com.toka.app.data.sync.SyncEngine
import com.toka.app.data.sync.SyncWorker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

class AppContainer private constructor(application: Application) {

    val tokenStore: TokenStore
    private val appContext = application.applicationContext
    private val dao = TokaDatabase.get(application).dao()

    lateinit var syncEngine: SyncEngine
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var taskRepository: TaskRepository
        private set
    lateinit var peopleRepository: PeopleRepository
        private set

    // El Retrofit activo. Los repositorios lo leen a través de un lambda en vez de
    // guardarlo, así cambiar de servidor (reconnect) no deja referencias viejas.
    private lateinit var api: TokaApi

    init {
        tokenStore = TokenStore(application.applicationContext)

        syncEngine = SyncEngine({ api }, dao, tokenStore)
        authRepository = AuthRepository({ api }, tokenStore)
        taskRepository = TaskRepository(dao, syncEngine, appContext)
        peopleRepository = PeopleRepository({ api }, tokenStore, dao, syncEngine, appContext)

        val storedUrl = runBlocking { tokenStore.getServerUrl() }
        api = buildApi(storedUrl ?: BuildConfig.BASE_URL)
    }

    private fun buildApi(baseUrl: String): TokaApi {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // BODY expone tokens y datos en logcat: solo en debug.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(TokaApi::class.java)
    }

    fun reconnect(url: String) {
        val normalized = normalizeBaseUrl(url)
        runBlocking {
            tokenStore.saveServerUrl(normalized)
            // Otro servidor es otro conjunto de datos. La caché del anterior no vale
            // y el cursor tampoco: se empieza de cero.
            syncEngine.reset()
        }
        api = buildApi(normalized)
        SyncWorker.syncNow(appContext)
    }

    /** Comprueba que en `url` responde un servidor Toka, sin guardar nada. */
    suspend fun checkServer(url: String): Result<Unit> = runCatching {
        buildApi(normalizeBaseUrl(url)).health()
    }.map { }

    /** Acepta "192.168.1.5:3000" y lo convierte en una base URL de Retrofit válida. */
    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    /** Fuerza un ciclo de sincronización (p. ej. apenas termina el onboarding). */
    fun syncNow() {
        SyncWorker.syncNow(appContext)
    }

    companion object {
        lateinit var instance: AppContainer
            private set

        fun init(application: Application) {
            instance = AppContainer(application)
        }
    }
}
