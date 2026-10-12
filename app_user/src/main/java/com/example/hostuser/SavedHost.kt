package com.example.hostuser

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tek kayitli sunucu adresi (id = 1).
 * TODO.md kurali: Tek kayit kurali veritabani anahtariyla (id = 1) korunur.
 */
@Entity(tableName = "saved_host")
data class SavedHost(
    @PrimaryKey
    val id: Int = 1,
    val url: String,
    val updatedAt: Long = System.currentTimeMillis()
)
