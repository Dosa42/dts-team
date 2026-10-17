package com.example.hostuser.buffer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BufferBridgeDao {

    // --- Outgoing Persistent Queue ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutgoing(message: BufferedMessage)

    @Update
    suspend fun updateOutgoing(message: BufferedMessage)

    @Query("SELECT * FROM buffered_messages WHERE id = :id LIMIT 1")
    suspend fun getOutgoingById(id: String): BufferedMessage?

    @Query("SELECT * FROM buffered_messages WHERE status IN ('PENDING', 'FAILED') ORDER BY sequenceNumber ASC, createdAt ASC")
    suspend fun getPendingOrFailedMessages(): List<BufferedMessage>

    @Query("SELECT * FROM buffered_messages WHERE status = 'SENT_TO_SOCKET' ORDER BY sequenceNumber ASC")
    suspend fun getInTransitMessages(): List<BufferedMessage>

    @Query("UPDATE buffered_messages SET status = :status, lastAttemptAt = :lastAttemptAt, retryCount = :retryCount WHERE id = :id")
    suspend fun updateOutgoingStatus(id: String, status: String, lastAttemptAt: Long, retryCount: Int)

    @Query("UPDATE buffered_messages SET status = 'DELIVERED' WHERE id = :id")
    suspend fun markDelivered(id: String)

    @Query("UPDATE buffered_messages SET status = 'PENDING' WHERE status = 'SENT_TO_SOCKET'")
    suspend fun resetInTransitToPending()

    @Query("DELETE FROM buffered_messages WHERE id = :id")
    suspend fun deleteOutgoing(id: String)

    @Query("SELECT COALESCE(MAX(sequenceNumber), 0) FROM buffered_messages")
    suspend fun getMaxSequenceNumber(): Long

    @Query("SELECT COUNT(*) FROM buffered_messages WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM buffered_messages WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM buffered_messages WHERE status = 'SENT_TO_SOCKET'")
    suspend fun getInTransitCount(): Int

    @Query("SELECT COUNT(*) FROM buffered_messages WHERE status = 'DELIVERED'")
    suspend fun getDeliveredCount(): Int

    @Query("SELECT * FROM buffered_messages ORDER BY sequenceNumber ASC, createdAt ASC")
    fun observeAllOutgoing(): Flow<List<BufferedMessage>>

    @Query("DELETE FROM buffered_messages WHERE status = 'DELIVERED' AND createdAt < :olderThan")
    suspend fun clearDelivered(olderThan: Long)

    // --- Incoming Registration & Deduplication ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceived(message: ReceivedMessage): Long

    @Query("SELECT * FROM received_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getReceived(messageId: String): ReceivedMessage?

    @Query("UPDATE received_messages SET status = :status, processedAt = :processedAt, responsePayload = :responsePayload WHERE messageId = :messageId")
    suspend fun updateReceivedStatus(messageId: String, status: String, processedAt: Long, responsePayload: String?)

    @Query("SELECT COUNT(*) FROM received_messages")
    fun observeReceivedCount(): Flow<Int>
}
