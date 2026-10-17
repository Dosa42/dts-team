package com.example.hostuser.buffer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Gelen mesaj ve komutların kaydedilmesi ve tekerrür (deduplication) önleme kaydı.
 */
@Entity(
    tableName = "received_messages",
    indices = [
        Index(value = ["receivedAt"]),
        Index(value = ["status"])
    ]
)
data class ReceivedMessage(
    @PrimaryKey
    val messageId: String,
    val payload: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val processedAt: Long? = null,
    val status: String = "RECEIVED", // RECEIVED, PROCESSED, DUPLICATE_SKIPPED, FAILED
    val responsePayload: String? = null
)
