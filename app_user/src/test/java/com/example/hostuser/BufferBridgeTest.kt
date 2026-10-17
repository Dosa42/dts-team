package com.example.hostuser

import com.example.hostuser.buffer.BufferBridge
import com.example.hostuser.buffer.BufferBridgeDao
import com.example.hostuser.buffer.BufferedMessage
import com.example.hostuser.buffer.DeliveryStatus
import com.example.hostuser.buffer.ReceivedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests verifying Buffer Bridge requirements:
 * 1. Messages are successfully stored while WebSocket is offline.
 * 2. Pending messages are automatically transmitted after reconnection in FIFO order.
 * 3. Queued messages survive application restarts (simulated via re-instantiation).
 * 4. Delivery status transitions (PENDING -> SENT_TO_SOCKET -> DELIVERED on ACK).
 * 5. Incoming message registration and deduplication (preventing duplicate execution).
 * 6. Socket send failures marked FAILED and retried upon reconnection.
 */
class BufferBridgeTest {

    private lateinit var fakeDao: FakeBufferBridgeDao

    @Before
    fun setUp() {
        fakeDao = FakeBufferBridgeDao()
    }

    @Test
    fun testMessagesStoredWhileOffline() = runBlocking {
        val bridge = BufferBridge(fakeDao, scope = CoroutineScope(Dispatchers.Default))

        // Soket kapalıyken mesaj kuyruğa alınıyor
        val msgId = bridge.enqueueOutgoing("{\"action\":\"test_offline\"}")

        // Room DB yazımının tamamlanması için bekleme
        waitForCondition(1500) { fakeDao.getPendingCount() == 1 }

        // 1. Durum: Mesaj Room veritabanında saklandı ve PENDING
        val stored = fakeDao.getOutgoingById(msgId)
        assertNotNull("Mesaj veritabanına kaydedilmeli", stored)
        assertEquals("Mesaj durumu PENDING olmalı", DeliveryStatus.PENDING.name, stored!!.status)
        assertEquals("Bekleyen mesaj sayısı 1 olmalı", 1, fakeDao.getPendingCount())
        assertEquals(0, stored.retryCount)
    }

    @Test
    fun testPendingMessagesTransmittedAfterReconnectionInOrder() = runBlocking {
        val bridge = BufferBridge(fakeDao, scope = CoroutineScope(Dispatchers.Default))

        // 3 farklı mesaj çevrimdışıyken sırayla kuyruğa alınıyor
        val id1 = bridge.enqueueOutgoing("msg_1")
        waitForCondition(1500) { fakeDao.getPendingCount() == 1 }

        val id2 = bridge.enqueueOutgoing("msg_2")
        waitForCondition(1500) { fakeDao.getPendingCount() == 2 }

        val id3 = bridge.enqueueOutgoing("msg_3")
        waitForCondition(1500) { fakeDao.getPendingCount() == 3 }

        assertEquals(3, fakeDao.getPendingCount())

        // Soket simülasyonu
        val fakeWebSocket = FakeWebSocket()

        // Soket bağlandı bildirimi veriliyor
        bridge.onWebSocketConnected(fakeWebSocket)

        waitForCondition(2500) { fakeWebSocket.sentMessages.size == 3 }

        // Mesajların sırayla iletildiğini doğrula
        assertEquals("3 mesaj iletilmiş olmalı", 3, fakeWebSocket.sentMessages.size)
        assertEquals("msg_1", fakeWebSocket.sentMessages[0])
        assertEquals("msg_2", fakeWebSocket.sentMessages[1])
        assertEquals("msg_3", fakeWebSocket.sentMessages[2])

        // Durum kontrolü: Kural 7 gereği soket kabul edince SENT_TO_SOCKET olur, DELIVERED değil
        val msg1 = fakeDao.getOutgoingById(id1)
        val msg2 = fakeDao.getOutgoingById(id2)
        val msg3 = fakeDao.getOutgoingById(id3)

        assertEquals(DeliveryStatus.SENT_TO_SOCKET.name, msg1!!.status)
        assertEquals(DeliveryStatus.SENT_TO_SOCKET.name, msg2!!.status)
        assertEquals(DeliveryStatus.SENT_TO_SOCKET.name, msg3!!.status)
        assertEquals(1, msg1.retryCount)
        assertNotNull(msg1.lastAttemptAt)
    }

    @Test
    fun testQueuedMessagesSurviveApplicationRestart() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)

        // Oturum 1: Mesajlar çevrimdışıyken kuyruğa alınıyor
        val bridge1 = BufferBridge(fakeDao, scope = scope)
        bridge1.enqueueOutgoing("survive_restart_1")
        waitForCondition(1500) { fakeDao.getPendingCount() == 1 }

        bridge1.enqueueOutgoing("survive_restart_2")
        waitForCondition(1500) { fakeDao.getPendingCount() == 2 }

        assertEquals(2, fakeDao.getPendingCount())

        // Oturum 2: Uygulama yeniden başlatılıyor (yeni BufferBridge örneği aynı veritabanıyla başlatılıyor)
        val bridge2 = BufferBridge(fakeDao, scope = scope)
        waitForCondition(1500) { fakeDao.getPendingCount() == 2 }

        assertEquals("Kayıtlı mesajlar yeniden başlatmada korunmalı", 2, fakeDao.getPendingCount())

        // Yeniden başlatma sonrası soket bağlandığında mesajlar aktarılmalı
        val fakeWebSocket = FakeWebSocket()
        bridge2.onWebSocketConnected(fakeWebSocket)

        waitForCondition(2500) { fakeWebSocket.sentMessages.size == 2 }
        assertEquals(2, fakeWebSocket.sentMessages.size)
        assertEquals("survive_restart_1", fakeWebSocket.sentMessages[0])
        assertEquals("survive_restart_2", fakeWebSocket.sentMessages[1])
    }

    @Test
    fun testAckConfirmationMarksDelivered() = runBlocking {
        val bridge = BufferBridge(fakeDao, scope = CoroutineScope(Dispatchers.Default))
        val id = bridge.enqueueOutgoing("payload_to_ack")
        waitForCondition(1500) { fakeDao.getPendingCount() == 1 }

        val fakeWebSocket = FakeWebSocket()
        bridge.onWebSocketConnected(fakeWebSocket)

        waitForCondition(2500) { fakeWebSocket.sentMessages.size == 1 }

        // Sokete verildi: SENT_TO_SOCKET
        assertEquals(DeliveryStatus.SENT_TO_SOCKET.name, fakeDao.getOutgoingById(id)!!.status)

        // Uzak sunucudan ACK mesajı geliyor
        bridge.handleIncomingMessage("{\"action\":\"ack\",\"id\":\"$id\"}")

        waitForCondition(2500) {
            fakeDao.getOutgoingById(id)?.status == DeliveryStatus.DELIVERED.name
        }

        // Durum DELIVERED olarak güncellenmeli
        assertEquals("ACK sonrası mesaj DELIVERED olmalı", DeliveryStatus.DELIVERED.name, fakeDao.getOutgoingById(id)!!.status)
    }

    @Test
    fun testIncomingMessageDeduplicationPreventsDuplicateExecution() = runBlocking {
        val bridge = BufferBridge(fakeDao, scope = CoroutineScope(Dispatchers.Default))

        // İlk geliş: komut işlenmeli
        val cmd = "{\"action\":\"shell\",\"cmd\":\"echo hi\",\"id\":\"cmd_unique_99\"}"
        bridge.handleIncomingMessage(cmd)

        waitForCondition(2500) {
            fakeDao.getReceived("cmd_unique_99") != null
        }

        val received = fakeDao.getReceived("cmd_unique_99")
        assertNotNull("Gelen mesaj kaydedilmeli", received)
        assertEquals("PROCESSED", received!!.status)

        // İkinci geliş: aynı id'li mesaj tekrar gelirse tekerrür önlenmeli
        bridge.handleIncomingMessage(cmd)
        delay(200)

        // Kayıt hala tek ve PROCESSED kalmalı
        assertEquals("PROCESSED", fakeDao.getReceived("cmd_unique_99")!!.status)
    }

    @Test
    fun testSocketFailureMarksFailedAndRetriesOnReconnect() = runBlocking {
        val bridge = BufferBridge(fakeDao, scope = CoroutineScope(Dispatchers.Default))
        val id = bridge.enqueueOutgoing("failing_message")
        waitForCondition(1500) { fakeDao.getPendingCount() == 1 }

        // Gönderimi reddeden hatalı soket simülasyonu
        val failingSocket = FakeWebSocket(failSends = true)
        bridge.onWebSocketConnected(failingSocket)

        waitForCondition(2500) {
            fakeDao.getOutgoingById(id)?.status == DeliveryStatus.FAILED.name
        }

        // Soket reddettiği için FAILED olmalı
        val failed = fakeDao.getOutgoingById(id)
        assertEquals(DeliveryStatus.FAILED.name, failed!!.status)

        // Şimdi sağlam soket bağlanıyor
        val workingSocket = FakeWebSocket(failSends = false)
        bridge.onWebSocketConnected(workingSocket)

        waitForCondition(2500) {
            workingSocket.sentMessages.size == 1
        }

        // Başarıyla aktarılmış olmalı
        assertEquals(1, workingSocket.sentMessages.size)
        assertEquals(DeliveryStatus.SENT_TO_SOCKET.name, fakeDao.getOutgoingById(id)!!.status)
        assertEquals(2, fakeDao.getOutgoingById(id)!!.retryCount)
    }

    private suspend fun waitForCondition(timeoutMs: Long, condition: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return
            delay(20)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    // --- Fake Test Sınıfları ---

    private class FakeWebSocket(val failSends: Boolean = false) : WebSocket {
        val sentMessages = mutableListOf<String>()

        override fun send(text: String): Boolean {
            if (failSends) return false
            sentMessages.add(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
        override fun queueSize(): Long = 0
        override fun request(): Request = Request.Builder().url("ws://localhost").build()
    }

    private class FakeBufferBridgeDao : BufferBridgeDao {
        private val outgoing = ConcurrentHashMap<String, BufferedMessage>()
        private val received = ConcurrentHashMap<String, ReceivedMessage>()
        private val outgoingFlow = MutableStateFlow<List<BufferedMessage>>(emptyList())

        override suspend fun insertOutgoing(message: BufferedMessage) {
            outgoing[message.id] = message
            outgoingFlow.value = outgoing.values.toList()
        }

        override suspend fun updateOutgoing(message: BufferedMessage) {
            outgoing[message.id] = message
            outgoingFlow.value = outgoing.values.toList()
        }

        override suspend fun getOutgoingById(id: String): BufferedMessage? = outgoing[id]

        override suspend fun getPendingOrFailedMessages(): List<BufferedMessage> {
            return outgoing.values
                .filter { it.status == DeliveryStatus.PENDING.name || it.status == DeliveryStatus.FAILED.name }
                .sortedWith(compareBy({ it.sequenceNumber }, { it.createdAt }))
        }

        override suspend fun getInTransitMessages(): List<BufferedMessage> {
            return outgoing.values.filter { it.status == DeliveryStatus.SENT_TO_SOCKET.name }
        }

        override suspend fun updateOutgoingStatus(id: String, status: String, lastAttemptAt: Long, retryCount: Int) {
            outgoing[id]?.let {
                outgoing[id] = it.copy(status = status, lastAttemptAt = lastAttemptAt, retryCount = retryCount)
                outgoingFlow.value = outgoing.values.toList()
            }
        }

        override suspend fun markDelivered(id: String) {
            outgoing[id]?.let {
                outgoing[id] = it.copy(status = DeliveryStatus.DELIVERED.name)
                outgoingFlow.value = outgoing.values.toList()
            }
        }

        override suspend fun resetInTransitToPending() {
            outgoing.values.forEach {
                if (it.status == DeliveryStatus.SENT_TO_SOCKET.name) {
                    outgoing[it.id] = it.copy(status = DeliveryStatus.PENDING.name)
                }
            }
            outgoingFlow.value = outgoing.values.toList()
        }

        override suspend fun deleteOutgoing(id: String) {
            outgoing.remove(id)
            outgoingFlow.value = outgoing.values.toList()
        }

        override suspend fun getMaxSequenceNumber(): Long {
            return outgoing.values.maxOfOrNull { it.sequenceNumber } ?: 0L
        }

        override fun observePendingCount(): Flow<Int> =
            outgoingFlow.map { list -> list.count { it.status == DeliveryStatus.PENDING.name } }

        override suspend fun getPendingCount(): Int =
            outgoing.values.count { it.status == DeliveryStatus.PENDING.name }

        override suspend fun getInTransitCount(): Int =
            outgoing.values.count { it.status == DeliveryStatus.SENT_TO_SOCKET.name }

        override suspend fun getDeliveredCount(): Int =
            outgoing.values.count { it.status == DeliveryStatus.DELIVERED.name }

        override fun observeAllOutgoing(): Flow<List<BufferedMessage>> = outgoingFlow

        override suspend fun clearDelivered(olderThan: Long) {
            outgoing.values.filter { it.status == DeliveryStatus.DELIVERED.name && it.createdAt < olderThan }
                .forEach { outgoing.remove(it.id) }
            outgoingFlow.value = outgoing.values.toList()
        }

        override suspend fun insertReceived(message: ReceivedMessage): Long {
            if (received.containsKey(message.messageId)) return -1
            received[message.messageId] = message
            return 1
        }

        override suspend fun getReceived(messageId: String): ReceivedMessage? = received[messageId]

        override suspend fun updateReceivedStatus(messageId: String, status: String, processedAt: Long, responsePayload: String?) {
            received[messageId]?.let {
                received[messageId] = it.copy(status = status, processedAt = processedAt, responsePayload = responsePayload)
            }
        }

        override fun observeReceivedCount(): Flow<Int> =
            MutableStateFlow(received.size)
    }
}
