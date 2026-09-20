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
    exportSchema = false
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
                    // La caché se puede reconstruir entera desde el servidor pidiendo
                    // since=0, así que ante un cambio de esquema se descarta y se
                    // vuelve a bajar. La excepción es la cola de salida: si hubiera
                    // mutaciones sin subir, se perderían. Hoy es aceptable porque la
                    // app no ha salido; en cuanto tenga usuarios, cada cambio de
                    // esquema necesita su Migration de verdad.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
