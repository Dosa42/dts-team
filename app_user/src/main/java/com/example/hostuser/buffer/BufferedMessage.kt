package com.example.hostuser.buffer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Teslimat durumları.
 * Not: SENT_TO_SOCKET, WebSocket.send() ile soket arabelleğine verildiğini belirtir;
 * DELIVERED ise uzak sunucudan ACK onayı geldiğinde atanır.
 */
enum class DeliveryStatus {
    PENDING,        // Veritabanında bekliyor, sokete henüz iletilmedi
    SENT_TO_SOCKET, // WebSocket.send() başarılı, uzak sunucu teyidi bekleniyor
    DELIVERED,      // Uzak sunucu ACK mesajı ile teslimatı onayladı
    FAILED          // İletim denemesi başarısız oldu
}

/**
 * Kalıcı arabellek kuyruğundaki giden mesaj kaydı.
 */
@Entity(
    tableName = "buffered_messages",
    indices = [
        Index(value = ["status", "createdAt"]),
        Index(value = ["sequenceNumber"])
    ]
)
data class BufferedMessage(
    @PrimaryKey
    val id: String,
    val payload: String,
    val status: String = DeliveryStatus.PENDING.name,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long? = null,
    val retryCount: Int = 0,
    val sequenceNumber: Long = 0L
)
