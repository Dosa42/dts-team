package com.example.hostuser.buffer

import android.util.Log
import com.example.hostuser.DeviceCommandExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.WebSocket
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * DTS-TEAM Native Buffer Bridge.
 *
 * Android uygulaması, yerel Room veritabanı ve WebSocket istemcisi arasında
 * çift yönlü, kalıcı ve iş parçacığı güvenli (thread-safe) arabellek köprüsü.
 *
 * Özellikler:
 * 1. Kalıcı giden mesaj kuyruğu (Room DB)
 * 2. Gelen mesajların kaydedilmesi ve tekerrürün (duplicate) önlenmesi
 * 3. Soket bağlandığında kuyruğun otomatik, sıralı (FIFO) iletilmesi
 * 4. Çevrimdışı kalıcılık ve uygulama yeniden başlatmalarında veri kaybı önleme
 * 5. Mesaj kimliği, teslimat durumu, zaman damgası ve iletim denemelerinin takibi
 * 6. Eşzamanlı yarış durumlarına (race condition) karşı Mutex ve Job senkronizasyonu
 * 7. Doğru teslimat durumları: WebSocket.send() onayı soket arabelleğine iletildiğini gösterir
 *    (SENT_TO_SOCKET); uzak sunucu onayı (ACK) gelmeden DELIVERED yapılmaz.
 */
class BufferBridge(
    private val dao: BufferBridgeDao,
    private var commandExecutor: DeviceCommandExecutor? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    companion object {
        private const val TAG = "BufferBridge"

        @Volatile
        private var INSTANCE: BufferBridge? = null

        fun getInstance(dao: BufferBridgeDao, executor: DeviceCommandExecutor? = null): BufferBridge {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BufferBridge(dao, executor).also { INSTANCE = it }
            }
        }
    }

    private val queueMutex = Mutex()
    private val sequenceCounter = AtomicLong(0)
    private val initJob: Job

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var isSocketConnected: Boolean = false

    // Gözlemlenebilir sayaçlar (UI ve telemetri için)
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _inTransitCount = MutableStateFlow(0)
    val inTransitCount: StateFlow<Int> = _inTransitCount.asStateFlow()

    private val _deliveredCount = MutableStateFlow(0)
    val deliveredCount: StateFlow<Int> = _deliveredCount.asStateFlow()

    init {
        initJob = scope.launch {
            try {
                val maxSeq = dao.getMaxSequenceNumber()
                sequenceCounter.set(maxSeq)
                // Uygulama açılışında önceki oturumdan kalan soket arabelleğindeki
                // teyitsiz mesajları yeniden PENDING durumuna çek
                dao.resetInTransitToPending()
                refreshCounts()
            } catch (e: Exception) {
                Log.e(TAG, "BufferBridge init failed: ${e.message}", e)
            }
        }
    }

    fun setCommandExecutor(executor: DeviceCommandExecutor) {
        this.commandExecutor = executor
    }

    /**
     * Giden mesajı yerel veritabanına kalıcı olarak ekler.
     * Soket o anda bağlıysa kuyruk aktarımını anında tetikler;
     * bağlı değilse mesaj yerel olarak güvenle saklanır.
     */
    fun enqueueOutgoing(payload: String, explicitId: String? = null): String {
        val messageId = explicitId ?: UUID.randomUUID().toString()
        val seq = sequenceCounter.incrementAndGet()
        val now = System.currentTimeMillis()

        val buffered = BufferedMessage(
            id = messageId,
            payload = payload,
            status = DeliveryStatus.PENDING.name,
            createdAt = now,
            lastAttemptAt = null,
            retryCount = 0,
            sequenceNumber = seq
        )

        scope.launch {
            try {
                initJob.join()
                dao.insertOutgoing(buffered)
                Log.i(TAG, "Enqueued outgoing message: id=$messageId, seq=$seq (connected=$isSocketConnected)")
                refreshCounts()
                if (isSocketConnected) {
                    processQueue()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue outgoing message: ${e.message}", e)
            }
        }

        return messageId
    }

    /**
     * WebSocket bağlantısı kurulduğunda çağrılır.
     * Bekleyen ve teyitsiz tüm mesajları sırayla gönderir.
     */
    fun onWebSocketConnected(webSocket: WebSocket) {
        Log.i(TAG, "WebSocket connected, initiating queue flush...")
        activeWebSocket = webSocket
        isSocketConnected = true

        scope.launch {
            try {
                initJob.join()
                // Kopma anında sokette kalmış teyitsiz mesajları PENDING'e al
                dao.resetInTransitToPending()
                refreshCounts()
                processQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling websocket connection in bridge: ${e.message}", e)
            }
        }
    }

    /**
     * WebSocket bağlantısı koptuğunda çağrılır.
     */
    fun onWebSocketDisconnected() {
        Log.i(TAG, "WebSocket disconnected")
        isSocketConnected = false
        activeWebSocket = null

        scope.launch {
            try {
                initJob.join()
                // Bağlantı koptuğu için henüz teyit edilmemiş soketteki mesajları PENDING'e geri al
                dao.resetInTransitToPending()
                refreshCounts()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling websocket disconnection in bridge: ${e.message}", e)
            }
        }
    }

    /**
     * Kuyruktaki bekleyen giden mesajları sırayla WebSocket üzerinden aktarır.
     * Mutex ile tek bir eşzamanlı döngü garanti edilir.
     */
    suspend fun processQueue() = queueMutex.withLock {
        initJob.join()

        val socket = activeWebSocket
        if (socket == null || !isSocketConnected) {
            Log.d(TAG, "processQueue skipped: socket not available")
            return@withLock
        }

        val pendingList = dao.getPendingOrFailedMessages()
        if (pendingList.isEmpty()) {
            return@withLock
        }

        Log.i(TAG, "Processing outgoing queue: ${pendingList.size} messages pending")

        for (message in pendingList) {
            // Soket durumu döngü sırasında değişmişse durdur
            val currentSocket = activeWebSocket
            if (currentSocket == null || !isSocketConnected) {
                Log.w(TAG, "Socket connection lost during queue processing, stopping.")
                break
            }

            val now = System.currentTimeMillis()
            val sendAccepted = try {
                currentSocket.send(message.payload)
            } catch (e: Exception) {
                Log.e(TAG, "Exception while sending message ${message.id}: ${e.message}", e)
                false
            }

            if (sendAccepted) {
                // WebSocket.send() soket arabelleğine başarıyla aktarıldığını bildirdi.
                // Kural 7: Bu durum uzak sunucu teslimatı değildir; SENT_TO_SOCKET olarak işaretlenir.
                dao.updateOutgoingStatus(
                    id = message.id,
                    status = DeliveryStatus.SENT_TO_SOCKET.name,
                    lastAttemptAt = now,
                    retryCount = message.retryCount + 1
                )
                Log.d(TAG, "Message accepted by socket buffer: id=${message.id}, attempt=${message.retryCount + 1}")
            } else {
                // Soket mesajı kabul edemedi (arabellek dolu veya soket kapandı)
                dao.updateOutgoingStatus(
                    id = message.id,
                    status = DeliveryStatus.FAILED.name,
                    lastAttemptAt = now,
                    retryCount = message.retryCount + 1
                )
                Log.w(TAG, "Socket rejected message: id=${message.id}, halting queue flush")
                break
            }
        }

        refreshCounts()
    }

    /**
     * Gelen mesajı işler:
     * 1. ACK kontrolü yapar (varsa ilgili giden mesajı DELIVERED olarak onaylar)
     * 2. Mesaj kimliğini çıkarır ve Room üzerinde tekerrür (deduplication) kontrolü yapar
     * 3. Tekrar eden komutların çift çalışmasını önler
     * 4. Komutu DeviceCommandExecutor ile çalıştırır
     * 5. Varsa cevabı Buffer Bridge üzerinden kalıcı olarak kuyruğa alır
     */
    fun handleIncomingMessage(rawText: String): String? {
        val now = System.currentTimeMillis()
        Log.i(TAG, "Handling incoming message: ${rawText.take(150)}")

        // 1. ACK Kontrolü
        val ackId = extractAckMessageId(rawText)
        if (ackId != null) {
            scope.launch {
                initJob.join()
                dao.markDelivered(ackId)
                Log.i(TAG, "Remote delivery ACK confirmed for message: $ackId")
                refreshCounts()
            }
            return null
        }

        // 2. Mesaj ID tespiti ve tekerrür kontrolü
        val (messageId, isIdempotentCommand) = determineIncomingMessageId(rawText)

        // 3. Asenkron işleme ve tekerrür önleme
        scope.launch {
            try {
                initJob.join()
                val existing = dao.getReceived(messageId)
                if (existing != null && existing.status == "PROCESSED" && !isIdempotentCommand) {
                    Log.w(TAG, "Duplicate message detected: id=$messageId. Skipping duplicate execution.")
                    return@launch
                }

                // Gelen kaydı ekle
                dao.insertReceived(
                    ReceivedMessage(
                        messageId = messageId,
                        payload = rawText,
                        receivedAt = now,
                        status = "RECEIVED"
                    )
                )

                // 4. Komutu çalıştır
                val executor = commandExecutor
                val response = if (executor != null) {
                    try {
                        executor.handleIncomingMessage(rawText)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing incoming command: ${e.message}", e)
                        "error: ${e.message}"
                    }
                } else {
                    Log.w(TAG, "No CommandExecutor set for incoming message.")
                    null
                }

                // 5. Gelen kaydını güncellendi ve tamamlandı olarak işaretle
                dao.updateReceivedStatus(
                    messageId = messageId,
                    status = "PROCESSED",
                    processedAt = System.currentTimeMillis(),
                    responsePayload = response
                )

                // 6. Yanıt varsa Buffer Bridge üzerinden güvenle gönder
                if (!response.isNullOrBlank()) {
                    enqueueOutgoing(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in handleIncomingMessage coroutine: ${e.message}", e)
            }
        }

        return null
    }

    /**
     * Uzak sunucudan gelen teslimat teyidi (ACK) mesajlarını tespit eder.
     */
    private fun extractAckMessageId(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                // 1. Yol: JSONObject
                val json = JSONObject(trimmed)
                val action = json.optString("action", "")
                val type = json.optString("type", "")
                val status = json.optString("status", "")

                if (action.equals("ack", ignoreCase = true) ||
                    type.equals("ack", ignoreCase = true) ||
                    status.equals("delivered", ignoreCase = true) ||
                    json.has("ack")
                ) {
                    val id = json.optString("id", "")
                        .ifEmpty { json.optString("msg_id", "") }
                        .ifEmpty { json.optString("message_id", "") }
                        .ifEmpty { json.optString("ack", "") }
                    if (id.isNotEmpty()) return id
                }
            } catch (_: Exception) {}

            // 2. Yol: Regex yedekleme (JVM testlerinde stublanmış JSONObject durumunda)
            val ackPattern = Regex("\"(?:action|type|status)\"\\s*:\\s*\"(?:ack|delivered)\"", RegexOption.IGNORE_CASE)
            val directAck = Regex("\"ack\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            if (ackPattern.containsMatchIn(trimmed) || directAck.containsMatchIn(trimmed)) {
                val direct = directAck.find(trimmed)?.groupValues?.get(1)
                if (!direct.isNullOrEmpty()) return direct

                val idMatch = Regex("\"(?:id|msg_id|message_id)\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(trimmed)
                val found = idMatch?.groupValues?.get(1)
                if (!found.isNullOrEmpty()) return found
            }
        } else if (trimmed.startsWith("ack:", ignoreCase = true)) {
            return trimmed.substring(4).trim()
        } else if (trimmed.startsWith("ack ", ignoreCase = true)) {
            return trimmed.substring(4).trim()
        }
        return null
    }

    /**
     * Gelen mesajın kimliğini ve komutun durumunu belirler.
     * JSON içindeki id / msg_id alanlarını öncelikli kabul eder;
     * yoksa yükün hash'ini çıkarır.
     */
    private fun determineIncomingMessageId(rawText: String): Pair<String, Boolean> {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            // 1. Yol: JSONObject üzerinden kontrol
            var explicitId = ""
            var action = ""
            try {
                val json = JSONObject(trimmed)
                explicitId = json.optString("id", "")
                    .ifEmpty { json.optString("msg_id", "") }
                    .ifEmpty { json.optString("message_id", "") }
                    .ifEmpty { json.optString("cmd_id", "") }
                    .ifEmpty { json.optString("request_id", "") }
                action = json.optString("action", "").lowercase()
            } catch (_: Exception) {}

            // 2. Yol: Regex kontrolü (JVM test veya parser yedeklemesi)
            if (explicitId.isEmpty()) {
                val idMatch = Regex("\"(?:id|msg_id|message_id|cmd_id|request_id)\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(trimmed)
                explicitId = idMatch?.groupValues?.get(1) ?: ""
            }
            if (action.isEmpty()) {
                val actionMatch = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(trimmed)
                action = actionMatch?.groupValues?.get(1)?.lowercase() ?: ""
            }

            val isIdempotent = action == "ping" || action == "info" || action == "status" || action == "telemetry"

            if (explicitId.isNotEmpty()) {
                return Pair(explicitId, false)
            }

            if (isIdempotent) {
                // Ping gibi idempotent sorgular her geldiğinde çalışabilmeli
                val ephemeralId = "query_${action}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                return Pair(ephemeralId, true)
            }

            // ID yoksa komut yükünün SHA-256 hash'ini tekerrür önleme anahtarı olarak kullan
            val hash = sha256(trimmed)
            return Pair("cmd_hash_$hash", false)
        }

        // Düz metin komutlar
        val lower = trimmed.lowercase()
        val isIdempotent = lower == "ping" || lower == "info" || lower == "telemetry"
        if (isIdempotent) {
            val ephemeralId = "text_${lower}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            return Pair(ephemeralId, true)
        }

        val hash = sha256(trimmed)
        return Pair("text_cmd_$hash", false)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun refreshCounts() {
        try {
            _pendingCount.value = dao.getPendingCount()
            _inTransitCount.value = dao.getInTransitCount()
            _deliveredCount.value = dao.getDeliveredCount()
        } catch (_: Exception) {}
    }
}
