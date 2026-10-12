package com.example.hostuser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class UiState {
    data object Loading : UiState()
    data class Setup(
        val inputUrl: String = "",
        val errorMessage: String? = null,
        val isSaving: Boolean = false
    ) : UiState()
    data class Viewing(val url: String) : UiState()
    data class ErrorState(val failedUrl: String, val description: String) : UiState()
}

/**
 * Host durum makinesi ve Room veri akisini yoneten ViewModel.
 * TODO.md kurali:
 * 1. Kayitli URL yoksa kurulum ekrani
 * 2. URL Room'a kaydedildikten sonra gosterim
 * 3. Sonraki acilislarda otomatik yukleme
 * 4. Adresi degistirme / sifirlama
 * 5. Hata durumunda yeniden dene ve adresi degistir
 */
class MainViewModel(private val repository: HostRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var currentSavedUrl: String? = null

    init {
        loadInitialState()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val saved = repository.savedHostFlow.first()
            if (saved != null && saved.url.isNotBlank()) {
                currentSavedUrl = saved.url
                _uiState.value = UiState.Viewing(saved.url)
            } else {
                currentSavedUrl = null
                _uiState.value = UiState.Setup()
            }
        }
    }

    fun onUrlInputChanged(newInput: String) {
        val current = _uiState.value
        if (current is UiState.Setup) {
            _uiState.value = current.copy(inputUrl = newInput, errorMessage = null)
        }
    }

    fun saveHost(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = UiState.Setup(inputUrl = rawInput, errorMessage = "Lütfen bir sunucu adresi girin.")
            return
        }

        _uiState.value = UiState.Setup(inputUrl = rawInput, isSaving = true)

        viewModelScope.launch {
            try {
                repository.saveHost(trimmed)
                currentSavedUrl = trimmed
                _uiState.value = UiState.Viewing(trimmed)
            } catch (e: Exception) {
                _uiState.value = UiState.Setup(
                    inputUrl = rawInput,
                    errorMessage = "Adres kaydedilemedi: ${e.message}",
                    isSaving = false
                )
            }
        }
    }

    /**
     * Adresi sifirlar: Room kaydini siler, aktif baglantiyi koparir ve kurulum ekranina doner.
     */
    fun resetHost() {
        viewModelScope.launch {
            try {
                repository.deleteHost()
            } catch (_: Exception) {}
            currentSavedUrl = null
            UserCore.nativeDisconnect()
            _uiState.value = UiState.Setup()
        }
    }

    fun reportError(failedUrl: String, description: String) {
        _uiState.value = UiState.ErrorState(failedUrl, description)
    }

    fun retry() {
        val url = currentSavedUrl
        if (!url.isNullOrBlank()) {
            _uiState.value = UiState.Viewing(url)
        } else {
            _uiState.value = UiState.Setup()
        }
    }

    companion object {
        fun provideFactory(repository: HostRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(repository) as T
                }
            }
    }
}
