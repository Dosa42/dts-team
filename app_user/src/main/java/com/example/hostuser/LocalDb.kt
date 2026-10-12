package com.example.hostuser

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Tek adres depolayan Room veritabani.
 * TODO.md kurali: LocalDb.kt surum 1, singleton ornek uzerinden HostDao erisimi.
 */
@Database(entities = [SavedHost::class], version = 1, exportSchema = false)
abstract class LocalDb : RoomDatabase() {

    abstract fun hostDao(): HostDao

    companion object {
        @Volatile
        private var INSTANCE: LocalDb? = null

        fun getDatabase(context: Context): LocalDb {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalDb::class.java,
                    "host_database"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
