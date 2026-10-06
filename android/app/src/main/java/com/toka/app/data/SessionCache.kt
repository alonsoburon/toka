package com.toka.app.data

import android.content.Context
import androidx.core.content.edit

/**
 * Copia síncrona de "quién soy y de qué hogar" para lo que corre sin esperar a Firestore:
 * los workers de recordatorios, el widget y las acciones de las notificaciones. La escribe
 * [com.toka.app.data.repository.SessionRepository] cada vez que cambia la sesión.
 */
class SessionCache(context: Context) {
    private val prefs = context.getSharedPreferences("toka_session", Context.MODE_PRIVATE)

    val uid: String? get() = prefs.getString("uid", null)
    val householdId: String? get() = prefs.getString("household_id", null)

    fun save(uid: String?, householdId: String?) = prefs.edit {
        if (uid == null) remove("uid") else putString("uid", uid)
        if (householdId == null) remove("household_id") else putString("household_id", householdId)
    }

    fun clear() = prefs.edit { clear() }
}
