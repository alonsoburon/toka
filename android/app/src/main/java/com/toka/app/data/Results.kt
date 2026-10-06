package com.toka.app.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * Como `runCatching`, pero deja pasar la cancelación. Con `runCatching` a secas, cancelar
 * una corrutina (WorkManager parando un worker, un ViewModel que se destruye) quedaba
 * convertido en un `Result.failure` más y el trabajo seguía como si nada.
 */
inline fun <T> suspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
