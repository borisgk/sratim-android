package com.example.sratim.ui.playback

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sratim.data.ConfigManager
import com.example.sratim.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val videoUrl: String? = null,
    val error: String? = null
)

class PlaybackViewModel(
    private val application: Application,
    private val configManager: ConfigManager,
    private val movieId: Int
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    init {
        preparePlayback()
    }

    private fun preparePlayback() {
        viewModelScope.launch {
            val config = configManager.configFlow.first()
            if (config.isValid) {
                val serverBaseUrl = NetworkClient.getBaseUrl(application, config.serverUrl)
                // Endpoint from spec Section 6
                val videoUrl = "${serverBaseUrl}api/v1/play?id=$movieId"
                _uiState.value = PlaybackUiState(videoUrl = videoUrl)
            } else {
                _uiState.value = PlaybackUiState(error = "Not configured")
            }
        }
    }
}
