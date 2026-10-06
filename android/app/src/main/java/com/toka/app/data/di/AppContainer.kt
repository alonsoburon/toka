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
import com.toka.app.data.suspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    @Volatile
    private lateinit var api: TokaApi

    // Un solo cliente HTTP (pool de conexiones y threads compartidos) para todas las
    // llamadas, incluidas las de comprobar un servidor.
    private val httpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // BODY expone datos en logcat: solo en debug, y sin el token.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }
        OkHttpClient.Builder().addInterceptor(loggingInterceptor).build()
    }

    init {
        tokenStore = TokenStore(application.applicationContext)

        syncEngine = SyncEngine({ api }, dao, tokenStore) {
            com.toka.app.widget.TodayWidgetProvider.refresh(appContext)
        }
        authRepository = AuthRepository({ api }, tokenStore)
        taskRepository = TaskRepository(dao, syncEngine, appContext)
        peopleRepository = PeopleRepository({ api }, tokenStore, dao, syncEngine, appContext)

        val storedUrl = runBlocking { tokenStore.getServerUrl() }
        api = buildApi(storedUrl ?: BuildConfig.BASE_URL)
    }

    private fun buildApi(baseUrl: String): TokaApi {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(TokaApi::class.java)
    }

    /**
     * Cambia de servidor. Valida la URL antes de tocar nada (Retrofit lanza si es
     * inválida, y antes eso ocurría después de borrar la caché local) y corre en IO.
     */
    suspend fun reconnect(url: String): Result<Unit> = suspendCatching {
        val normalized = normalizeBaseUrl(url)
        requireNotNull(normalized.toHttpUrlOrNull()) { "URL de servidor inválida: $url" }
        val newApi = buildApi(normalized)
        withContext(Dispatchers.IO) {
            tokenStore.saveServerUrl(normalized)
            // Otro servidor es otro conjunto de datos. La caché del anterior no vale
            // y el cursor tampoco: se empieza de cero.
            syncEngine.reset()
        }
        api = newApi
    }

    /** Comprueba que en `url` responde un servidor Toka, sin guardar nada. */
    suspend fun checkServer(url: String): Result<Unit> = suspendCatching {
        val normalized = normalizeBaseUrl(url)
        requireNotNull(normalized.toHttpUrlOrNull()) { "URL de servidor inválida: $url" }
        buildApi(normalized).health()
    }.map { }

    /**
     * Acepta "toka.example.com" y lo convierte en una base URL de Retrofit válida. Sin
     * esquema se asume https; solo el build debug (que permite cleartext para el
     * emulador y la LAN) asume http.
     */
    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            (if (BuildConfig.DEBUG) "http://" else "https://") + trimmed
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    /** Fuerza un ciclo de sincronización (p. ej. apenas termina el onboarding). */
    fun syncNow() {
        syncEngine.kick(appContext)
    }

    companion object {
        lateinit var instance: AppContainer
            private set

        fun init(application: Application) {
            instance = AppContainer(application)
        }
    }
}
