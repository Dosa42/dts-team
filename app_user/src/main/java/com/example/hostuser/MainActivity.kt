package com.example.hostuser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hostuser.ui.ErrorScreen
import com.example.hostuser.ui.HostUserTheme
import com.example.hostuser.ui.SetupScreen
import com.example.hostuser.ui.ViewingScreen
import java.io.File
import java.util.concurrent.Executors

/**
 * DTS-TEAM.v1 Host Client Ana Aktivitesi.
 *
 * TODO.md kurali:
 * 1. Kayitli URL yoksa kurulum ekrani
 * 2. URL kaydedildikten sonra gosterim
 * 3. Otomatik yukleme
 * 4. Adresi degistirme / sifirlama
 * 5. Hata durumunda yeniden dene ve adresi degistir
 */
class MainActivity : ComponentActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private val viewModel: MainViewModel by viewModels {
        val db = LocalDb.getDatabase(applicationContext)
        val repo = HostRepository(db.hostDao())
        MainViewModel.provideFactory(repo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val caPath = copyCaToFiles()
        val executor = DeviceCommandExecutor(applicationContext)
        val db = LocalDb.getDatabase(applicationContext)
        val bridge = com.example.hostuser.buffer.BufferBridge.getInstance(db.bufferBridgeDao(), executor)

        UserCore.setCommandExecutor(executor)
        UserCore.setBufferBridge(bridge)
        io.execute { UserCore.nativeInit(caPath) }

        setContent {
            HostUserTheme {
                val state by viewModel.uiState.collectAsState()
                MainContent(
                    state = state,
                    onUrlChanged = { viewModel.onUrlInputChanged(it) },
                    onSave = { viewModel.saveHost(it) },
                    onReset = { viewModel.resetHost() },
                    onRetry = { viewModel.retry() },
                    onError = { url, desc -> viewModel.reportError(url, desc) }
                )
            }
        }
    }

    @Composable
    private fun MainContent(
        state: UiState,
        onUrlChanged: (String) -> Unit,
        onSave: (String) -> Unit,
        onReset: () -> Unit,
        onRetry: () -> Unit,
        onError: (String, String) -> Unit
    ) {
        when (state) {
            is UiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            is UiState.Setup -> {
                SetupScreen(
                    state = state,
                    onUrlChanged = onUrlChanged,
                    onSave = onSave
                )
            }
            is UiState.Viewing -> {
                ViewingScreen(
                    url = state.url,
                    onResetHost = onReset,
                    onErrorOccurred = onError
                )
            }
            is UiState.ErrorState -> {
                ErrorScreen(
                    state = state,
                    onRetry = onRetry,
                    onResetHost = onReset
                )
            }
        }
    }

    private fun copyCaToFiles(): String {
        return try {
            val out = File(filesDir, "cacert.pem")
            assets.open("cacert.pem").use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            out.absolutePath
        } catch (_: Exception) {
            ""
        }
    }
}
