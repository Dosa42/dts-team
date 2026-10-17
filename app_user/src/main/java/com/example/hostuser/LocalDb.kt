package com.example.hostuser

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.hostuser.buffer.BufferBridgeDao
import com.example.hostuser.buffer.BufferedMessage
import com.example.hostuser.buffer.ReceivedMessage

/**
 * Tek adres depolayan ve Buffer Bridge ile kalici iletisim kuyrugunu yoneten Room veritabani.
 * Surum 2: BufferBridge tablolarini (buffered_messages ve received_messages) ekler.
 * MIGRATION_1_2 ile kayitli sunucu adresi ve ayarlari sifirlanmadan korunur.
 */
@Database(
    entities = [
        SavedHost::class,
        BufferedMessage::class,
        ReceivedMessage::class
    ],
    version = 2,
    exportSchema = false
)
abstract class LocalDb : RoomDatabase() {

    abstract fun hostDao(): HostDao
    abstract fun bufferBridgeDao(): BufferBridgeDao

    companion object {
        @Volatile
        private var INSTANCE: LocalDb? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `buffered_messages` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `payload` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastAttemptAt` INTEGER,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `sequenceNumber` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `received_messages` (
                        `messageId` TEXT NOT NULL PRIMARY KEY,
                        `payload` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `processedAt` INTEGER,
                        `status` TEXT NOT NULL,
                        `responsePayload` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_buffered_messages_status_createdAt` ON `buffered_messages` (`status`, `createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_buffered_messages_sequenceNumber` ON `buffered_messages` (`sequenceNumber`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_received_messages_receivedAt` ON `received_messages` (`receivedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_received_messages_status` ON `received_messages` (`status`)"
                )
            }
        }

        fun getDatabase(context: Context): LocalDb {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalDb::class.java,
                    "host_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
