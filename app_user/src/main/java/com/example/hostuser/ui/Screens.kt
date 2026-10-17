package com.example.hostuser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hostuser.UiState
import com.example.hostuser.UserCore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    state: UiState.Setup,
    onUrlChanged: (String) -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var textValue by remember(state.inputUrl) { mutableStateOf(state.inputUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Host Kurulumu", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_card")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Sunucu Bağlantısı",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tabletin bağlanacağı host adresini girin. Kaydedilen adres bir sonraki açılışta otomatik olarak yüklenecektir.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedTextField(
                        value = textValue,
                        onValueChange = {
                            textValue = it
                            onUrlChanged(it)
                        },
                        label = { Text("Sunucu Adresi") },
                        placeholder = { Text("ör. xxx.ngrok-free.app veya 192.168.1.50:8085") },
                        isError = state.errorMessage != null,
                        supportingText = {
                            if (state.errorMessage != null) {
                                Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("ngrok, https://, http://, ws:// veya wss:// desteklenir.")
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { onSave(textValue) }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("url_input_field")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                textValue = "https://"
                                onUrlChanged(textValue)
                            },
                            label = { Text("https://") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                textValue = "ws://"
                                onUrlChanged(textValue)
                            },
                            label = { Text("ws://") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                textValue = "localhost:8085"
                                onUrlChanged(textValue)
                            },
                            label = { Text("localhost") }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onSave(textValue) },
                        enabled = !state.isSaving && textValue.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_connect_button")
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Kaydet ve Bağlan", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ViewingScreen(
    url: String,
    onResetHost: () -> Unit,
    onErrorOccurred: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSocketUrl = url.startsWith("ws://", ignoreCase = true) ||
        url.startsWith("wss://", ignoreCase = true)

    if (isSocketUrl) {
        // WebSocket Modu
        val socketStatus by UserCore.status.collectAsState()

        LaunchedEffect(url) {
            UserCore.nativeConnect(url)
        }

        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "WebSocket Oturumu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Button(
                        onClick = onResetHost,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.testTag("reset_socket_button")
                    ) {
                        Text("Sıfırla")
                    }
                }
            },
            modifier = modifier
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val bridge = remember { UserCore.getBufferBridge() }
                val pendingCount by bridge?.pendingCount?.collectAsState() ?: remember { mutableStateOf(0) }
                val inTransitCount by bridge?.inTransitCount?.collectAsState() ?: remember { mutableStateOf(0) }
                val deliveredCount by bridge?.deliveredCount?.collectAsState() ?: remember { mutableStateOf(0) }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val statusText = when (val s = socketStatus) {
                            is UserCore.ConnectionStatus.Connected -> "Bağlandı (Komut dinleniyor)"
                            is UserCore.ConnectionStatus.Connecting -> "Bağlanılıyor..."
                            is UserCore.ConnectionStatus.Disconnected -> "Bağlantı Kesildi"
                            is UserCore.ConnectionStatus.Error -> "Hata: ${s.reason}"
                        }

                        Text(
                            text = "Durum: $statusText",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Gelen yönetim ve kabuk komutları bu tablet üzerinde otomatik çalıştırılır.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Buffer Bridge Kuyruk Durumu",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$pendingCount",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (pendingCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Bekleyen (Room)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$inTransitCount",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = "Sokette (Teyit Bekliyor)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$deliveredCount",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Onaylanan (ACK)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    UserCore.sendBufferedMessage("""{"action":"ping","timestamp":${System.currentTimeMillis()}}""")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("buffer_send_ping_button")
                            ) {
                                Text("Ping Kuyruğa Al", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = {
                                    UserCore.sendBufferedMessage("""{"action":"telemetry","client":"tablet","timestamp":${System.currentTimeMillis()}}""")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("buffer_send_telemetry_button")
                            ) {
                                Text("Telemetri Gönder", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    } else {
        // WebView Modu
        var webViewInstance by remember { mutableStateOf<WebView?>(null) }
        val webUrl = remember(url) { toWebUrl(url) }

        BackHandler(enabled = webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        }

        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                if (request.isForMainFrame) {
                                    onErrorOccurred(
                                        request.url.toString(),
                                        error.description?.toString() ?: "Bağlantı hatası"
                                    )
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                            }
                        }
                        webViewInstance = this
                        loadUrl(webUrl, mapOf("ngrok-skip-browser-warning" to "1"))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("host_webview")
            )

            // Kiosk uzerinde adresi degistirebilmek icin hafif saydam yonetim dugmesi
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                OutlinedButton(
                    onClick = onResetHost,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("change_host_button")
                ) {
                    Text("Adresi Sıfırla", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun ErrorScreen(
    state: UiState.ErrorState,
    onRetry: () -> Unit,
    onResetHost: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("error_card")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Bağlantı Başarısız",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = state.failedUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onResetHost,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("error_change_host_button")
                        ) {
                            Text("Adresi Değiştir")
                        }

                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("error_retry_button")
                        ) {
                            Text("Yeniden Dene")
                        }
                    }
                }
            }
        }
    }
}

private fun toWebUrl(s: String): String {
    val lower = s.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) return s
    val host = s.substringBefore('/').substringBefore(':')
    val isLocal = host == "localhost" ||
        host.endsWith(".local") ||
        Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)
    return (if (isLocal) "http://" else "https://") + s
}
