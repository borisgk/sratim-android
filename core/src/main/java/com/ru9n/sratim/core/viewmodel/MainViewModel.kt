package com.ru9n.sratim.core.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.Config
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.network.Library
import com.ru9n.sratim.core.network.LoginRequest
import com.ru9n.sratim.core.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecentlyWatchedUiModel(
    val id: Int,
    val type: String,
    val showId: Int? = null,
    val showTitle: String? = null,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String,
    val backdropUrl: String = "",
    val positionSeconds: Double,
    val durationSeconds: Double,
    val progressPercent: Float
)

data class MainUiState(
    val isLoading: Boolean = false,
    val libraries: List<Library> = emptyList(),
    val recentlyWatched: List<RecentlyWatchedUiModel> = emptyList(),
    val error: String? = null
)

class MainViewModel(
    private val application: Application,
    private val configManager: ConfigManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        fetchLibraries()
    }

    fun fetchLibraries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            val config = configManager.configFlow.filter { it.isValid }.first()
            Log.d("SratimNet", "Main: Using Token: ${config.token}")

            val api = NetworkClient.createApi(application, config.serverUrl, config.token)
            if (api == null) {
                _uiState.update { it.copy(isLoading = false, error = "Network client failed") }
                return@launch
            }

            try {
                val response = api.getLibraries()
                if (response.code() == 302 || response.code() == 401) {
                    Log.w("SratimNet", "Session expired (302/401), attempting auto-login")
                    performAutoLogin(config)
                    return@launch
                }

                val body = response.body()
                if (response.isSuccessful && body?.success == true) {
                    _uiState.update { it.copy(isLoading = false, libraries = body.libraries ?: emptyList()) }

                    // Fetch recently watched items
                    try {
                        val recentResponse = api.getRecentlyWatched()
                        if (recentResponse.isSuccessful && recentResponse.body()?.success == true) {
                            val serverBaseUrl = NetworkClient.getBaseUrl(application, config.serverUrl)
                            val recentItems = recentResponse.body()?.items.orEmpty().map { item ->
                                val effectivePoster = if (item.type == "episode") {
                                    item.showPosterPath?.takeIf { it.isNotEmpty() } ?: item.posterPath
                                } else {
                                    item.posterPath
                                }
                                val posterUrl = if (!effectivePoster.isNullOrEmpty()) {
                                    "${serverBaseUrl}images/posters/w185$effectivePoster"
                                } else ""
                                val backdropUrl = if (!item.backdropPath.isNullOrEmpty()) {
                                    "${serverBaseUrl}images/backdrops/original${item.backdropPath}"
                                } else ""
                                val progress = item.progressPercent?.toFloat() ?: if (item.duration != null && item.duration > 0 && item.position != null) {
                                    ((item.position / item.duration) * 100.0).toFloat().coerceIn(0f, 100f)
                                } else 0f
                                RecentlyWatchedUiModel(
                                    id = item.id,
                                    type = item.type,
                                    showId = item.showId,
                                    showTitle = item.showTitle,
                                    title = item.title,
                                    season = item.season,
                                    episode = item.episode,
                                    posterUrl = posterUrl,
                                    backdropUrl = backdropUrl,
                                    positionSeconds = item.position ?: 0.0,
                                    durationSeconds = item.duration ?: 0.0,
                                    progressPercent = progress
                                )
                            }
                            _uiState.update { it.copy(recentlyWatched = recentItems) }
                        }
                    } catch (e: Exception) {
                        Log.d("SratimNet", "Recently watched not supported or failed: ${e.message}")
                    }
                } else {
                    val serverError = body?.error ?: "No response body"
                    val tokenStatus = if (config.token.isEmpty()) "Empty" else "Len: ${config.token.length}"
                    Log.e("SratimNet", "Fetch failed: ${response.code()}, Token: $tokenStatus, Server Error: $serverError")
                    _uiState.update { it.copy(isLoading = false, error = "Error ${response.code()}: $serverError ($tokenStatus)") }
                }
            } catch (e: Exception) {
                Log.e("SratimNet", "Exception fetching libraries", e)
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    private suspend fun performAutoLogin(config: Config) {
        val api = NetworkClient.createApi(application, config.serverUrl)
        if (api == null) {
            handleAuthFailure("Could not initialize re-login")
            return
        }

        try {
            val response = api.login(LoginRequest(config.username, config.password))
            val body = response.body()
            val token = body?.token
            if (response.isSuccessful && body?.success == true && !token.isNullOrBlank()) {
                Log.i("SratimNet", "Auto-login successful, updating token")
                configManager.updateToken(token)
                // Retry fetching libraries with the new token
                fetchLibraries()
            } else {
                val error = body?.error ?: "Invalid credentials"
                Log.e("SratimNet", "Auto-login failed: $error")
                handleAuthFailure("Session expired and re-login failed: $error")
            }
        } catch (e: Exception) {
            Log.e("SratimNet", "Exception during auto-login", e)
            handleAuthFailure("Auto-login error: ${e.message}")
        }
    }

    private fun handleAuthFailure(message: String) {
        _uiState.update { it.copy(isLoading = false, error = message) }
        // If login fails, we might want to force setup again
        // viewModelScope.launch { configManager.clearConfig() }
    }

    fun logout() {
        viewModelScope.launch {
            configManager.clearConfig()
        }
    }
}
