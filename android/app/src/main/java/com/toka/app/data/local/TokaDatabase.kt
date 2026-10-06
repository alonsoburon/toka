package com.toka.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PersonEntity::class,
        TemplateEntity::class,
        TaskEntity::class,
        OutboxEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class TokaDatabase : RoomDatabase() {

    abstract fun dao(): TokaDao

    companion object {
        @Volatile private var instance: TokaDatabase? = null

        fun get(context: Context): TokaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TokaDatabase::class.java,
                    "toka.db"
                )
                    // Un cambio de esquema necesita su Migration (con los JSON de
                    // schemas/ para probarla). Ya hay APKs publicados, y descartar la
                    // base destruiría la cola de salida: escrituras sin subir. Solo un
                    // downgrade —que no tiene migración posible— reconstruye la caché.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
