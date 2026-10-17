package com.example.hostuser

import android.util.Log
import com.example.hostuser.buffer.BufferBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * WebSocket ve bağlantı yönetim çekirdeği.
 * Buffer Bridge entegrasyonu ile giden mesajlar soket kapalıyken yerel Room veritabanında
 * tutulur; soket açıldığında otomatik ve sıralı olarak aktarılır.
 * Gelen mesajlar Buffer Bridge üzerinden kaydedilir ve tekerrür önlenir.
 */
object UserCore {

    private const val TAG = "user_core"

    sealed class ConnectionStatus {
        data object Disconnected : ConnectionStatus()
        data class Connecting(val url: String) : ConnectionStatus()
        data class Connected(val url: String) : ConnectionStatus()
        data class Error(val reason: String) : ConnectionStatus()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private var activeWebSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var commandExecutor: DeviceCommandExecutor? = null
    private var bufferBridge: BufferBridge? = null

    private var targetUrl: String? = null
    private var userRequestedDisconnect: Boolean = false
    private var reconnectJob: Job? = null

    fun setCommandExecutor(executor: DeviceCommandExecutor) {
        commandExecutor = executor
        bufferBridge?.setCommandExecutor(executor)
    }

    fun setBufferBridge(bridge: BufferBridge) {
        bufferBridge = bridge
        commandExecutor?.let { bridge.setCommandExecutor(it) }
    }

    fun getBufferBridge(): BufferBridge? = bufferBridge

    /**
     * CA sertifikasini yukler ve SSL istemcisini hazirlar.
     */
    @JvmStatic
    fun nativeInit(caFilePath: String) {
        initClient(caFilePath)
    }

    /**
     * Verilen URL'e baglanir (ws://, wss://, http://, https://).
     */
    @JvmStatic
    fun nativeConnect(url: String) {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) {
            Log.e(TAG, "Connect: bos veya gecersiz adres: $url")
            _status.value = ConnectionStatus.Error("Bos veya gecersiz adres")
            return
        }

        userRequestedDisconnect = false
        targetUrl = normalized
        reconnectJob?.cancel()

        connectInternal(normalized)
    }

    private fun connectInternal(normalized: String) {
        disconnectSocketOnly()
        _status.value = ConnectionStatus.Connecting(normalized)
        Log.i(TAG, "connecting: $normalized")

        val client = okHttpClient ?: createDefaultClient().also { okHttpClient = it }

        val request = Request.Builder()
            .url(normalized)
            .addHeader("ngrok-skip-browser-warning", "1")
            .build()

        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "open: $normalized")
                _status.value = ConnectionStatus.Connected(normalized)
                bufferBridge?.onWebSocketConnected(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "message ${text.length}B: ${text.take(200)}")
                val bridge = bufferBridge
                if (bridge != null) {
                    bridge.handleIncomingMessage(text)
                } else {
                    scope.launch {
                        val response = commandExecutor?.handleIncomingMessage(text)
                        if (!response.isNullOrBlank()) {
                            webSocket.send(response)
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "close: $code $reason")
                bufferBridge?.onWebSocketDisconnected()
                _status.value = ConnectionStatus.Disconnected
                scheduleReconnectIfNeeded()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorReason = t.message ?: "Unknown socket error"
                Log.e(TAG, "error: $errorReason (http ${response?.code})", t)
                bufferBridge?.onWebSocketDisconnected()
                _status.value = ConnectionStatus.Error(errorReason)
                scheduleReconnectIfNeeded()
            }
        })
    }

    private fun scheduleReconnectIfNeeded() {
        if (userRequestedDisconnect) return
        val url = targetUrl ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnection scheduled in 4 seconds for: $url")
            delay(4000)
            if (!userRequestedDisconnect && targetUrl == url) {
                Log.i(TAG, "Attempting automatic reconnection to: $url")
                connectInternal(url)
            }
        }
    }

    /**
     * Giden mesajı Buffer Bridge üzerinden güvenli ve kalıcı olarak kuyruğa alır.
     * Soket bağlıysa hemen iletilir, kapalıysa bağlantı kurulduğunda gönderilir.
     */
    fun sendBufferedMessage(payload: String, explicitId: String? = null): String? {
        val bridge = bufferBridge
        return if (bridge != null) {
            bridge.enqueueOutgoing(payload, explicitId)
        } else {
            // Bridge henüz bağlı değilse geçici doğrudan gönderim
            activeWebSocket?.send(payload)
            null
        }
    }

    /**
     * Baglantiyi tamamen kapatir ve otomatik yeniden bağlanmayı durdurur.
     */
    @JvmStatic
    fun nativeDisconnect() {
        userRequestedDisconnect = true
        reconnectJob?.cancel()
        disconnectSocketOnly()
        bufferBridge?.onWebSocketDisconnected()
        _status.value = ConnectionStatus.Disconnected
        Log.i(TAG, "user requested disconnect")
    }

    private fun disconnectSocketOnly() {
        activeWebSocket?.let {
            it.close(1000, "Reset")
            it.cancel()
        }
        activeWebSocket = null
    }

    fun normalizeUrl(userInput: String): String {
        val s = userInput.trim()
        if (s.isEmpty()) return ""
        return when {
            s.startsWith("https://", ignoreCase = true) -> "wss://" + s.substring(8)
            s.startsWith("http://", ignoreCase = true) -> "ws://" + s.substring(7)
            s.startsWith("wss://", ignoreCase = true) || s.startsWith("ws://", ignoreCase = true) -> s
            else -> "wss://$s"
        }
    }

    private fun initClient(caFilePath: String) {
        try {
            val caFile = File(caFilePath)
            if (!caFile.exists()) {
                okHttpClient = createDefaultClient()
                return
            }

            val cf = CertificateFactory.getInstance("X.509")
            val cert = FileInputStream(caFile).use { cf.generateCertificate(it) as X509Certificate }

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca", cert)
            }

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }

            val trustManagers = tmf.trustManagers
            val x509TrustManager = trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
                ?: throw IllegalStateException("X509TrustManager bulunamadi")

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(x509TrustManager), null)
            }

            okHttpClient = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, x509TrustManager)
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            Log.i(TAG, "Client CA ile baslatildi: $caFilePath")
        } catch (e: Exception) {
            Log.w(TAG, "CA yuklenemedi, varsayilan kullaniliyor: ${e.message}")
            okHttpClient = createDefaultClient()
        }
    }

    private fun createDefaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
