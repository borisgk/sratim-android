package com.ru9n.sratim.core.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.Config
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.data.SratimDataCache
import com.ru9n.sratim.core.network.Library
import com.ru9n.sratim.core.network.LoginRequest
import com.ru9n.sratim.core.network.NetworkClient
import com.ru9n.sratim.core.network.RecentlyWatchedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val _uiState = MutableStateFlow(
        MainUiState(
            libraries = SratimDataCache.getLibraries().orEmpty(),
            recentlyWatched = SratimDataCache.getRecentlyWatched().orEmpty()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        fetchLibraries()
    }

    fun fetchLibraries() {
        viewModelScope.launch {
            val hasCachedData = _uiState.value.libraries.isNotEmpty()
            _uiState.update { it.copy(isLoading = !hasCachedData, error = null) }

            val config = withContext(Dispatchers.IO) {
                configManager.configFlow.filter { it.isValid }.first()
            }
            SratimDataCache.ensureSession(config)

            val api = withContext(Dispatchers.IO) {
                NetworkClient.createApi(application, config.serverUrl, config.token)
            }
            if (api == null) {
                _uiState.update { it.copy(isLoading = false, error = "Network client failed") }
                return@launch
            }

            try {
                // Fetch libraries and recently-watched concurrently over shared HTTP/2 pool
                val (response, recentResponse) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val libsDeferred = async { api.getLibraries() }
                        val recentDeferred = async {
                            runCatching { api.getRecentlyWatched() }.getOrNull()
                        }
                        libsDeferred.await() to recentDeferred.await()
                    }
                }

                if (response.code() == 302 || response.code() == 401) {
                    Log.w("SratimNet", "Session expired (302/401), attempting auto-login")
                    performAutoLogin(config)
                    return@launch
                }

                val body = response.body()
                if (response.isSuccessful && body?.success == true) {
                    val fetchedLibraries = body.libraries.orEmpty()
                    val serverBaseUrl = withContext(Dispatchers.IO) {
                        NetworkClient.getBaseUrl(application, config.serverUrl)
                    }

                    val fetchedRecent = if (recentResponse?.isSuccessful == true && recentResponse.body()?.success == true) {
                        val rawItems = recentResponse.body()?.items.orEmpty()
                        withContext(Dispatchers.Default) {
                            mapRecentlyWatchedItems(rawItems, serverBaseUrl)
                        }
                    } else {
                        _uiState.value.recentlyWatched
                    }

                    SratimDataCache.putLibraries(fetchedLibraries)
                    if (recentResponse?.isSuccessful == true && recentResponse.body()?.success == true) {
                        SratimDataCache.putRecentlyWatched(fetchedRecent)
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            libraries = fetchedLibraries,
                            recentlyWatched = fetchedRecent,
                            error = null
                        )
                    }
                } else if (!hasCachedData) {
                    val serverError = body?.error ?: "No response body"
                    val tokenStatus = if (config.token.isEmpty()) "Empty" else "Len: ${config.token.length}"
                    Log.e("SratimNet", "Fetch failed: ${response.code()}, Token: $tokenStatus, Server Error: $serverError")
                    _uiState.update { it.copy(isLoading = false, error = "Error ${response.code()}: $serverError ($tokenStatus)") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                Log.e("SratimNet", "Exception fetching libraries", e)
                if (!hasCachedData) {
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Immediately applies any in-memory watch progress updates recorded during playback
     * and triggers a silent background sync if playback occurred.
     */
    fun refreshRecentlyWatchedIfNeeded() {
        if (!SratimDataCache.consumeRecentlyWatchedDirty()) return

        SratimDataCache.getRecentlyWatched()?.let { cached ->
            _uiState.update { it.copy(recentlyWatched = cached) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = configManager.configFlow.first()
                if (!config.isValid) return@launch
                val api = NetworkClient.createApi(application, config.serverUrl, config.token) ?: return@launch
                val recentResponse = api.getRecentlyWatched()
                if (recentResponse.isSuccessful && recentResponse.body()?.success == true) {
                    val serverBaseUrl = NetworkClient.getBaseUrl(application, config.serverUrl)
                    val mapped = withContext(Dispatchers.Default) {
                        mapRecentlyWatchedItems(recentResponse.body()?.items.orEmpty(), serverBaseUrl)
                    }
                    SratimDataCache.putRecentlyWatched(mapped)
                    _uiState.update { it.copy(recentlyWatched = mapped) }
                }
            } catch (e: Exception) {
                Log.d("SratimNet", "Silent recently-watched refresh skipped: ${e.message}")
            }
        }
    }

    private fun mapRecentlyWatchedItems(
        items: List<RecentlyWatchedItem>,
        serverBaseUrl: String
    ): List<RecentlyWatchedUiModel> {
        return items.map { item ->
            val effectivePoster = if (item.type == "episode") {
                item.showPosterPath?.takeIf { it.isNotEmpty() } ?: item.posterPath
            } else {
                item.posterPath
            }
            val posterUrl = if (!effectivePoster.isNullOrEmpty()) {
                "${serverBaseUrl}images/posters/w185/${effectivePoster.removePrefix("/")}"
            } else ""
            val backdropUrl = if (!item.backdropPath.isNullOrEmpty()) {
                "${serverBaseUrl}images/backdrops/w780/${item.backdropPath.removePrefix("/")}"
            } else ""
            val dur = item.duration ?: 0.0
            val pos = item.position ?: 0.0
            val progress = item.progressPercent?.toFloat() ?: if (dur > 0.0 && pos > 0.0) {
                ((pos / dur) * 100.0).toFloat().coerceIn(0f, 100f)
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
                positionSeconds = pos,
                durationSeconds = dur,
                progressPercent = progress
            )
        }
    }

    private suspend fun performAutoLogin(config: Config) {
        val api = withContext(Dispatchers.IO) {
            NetworkClient.createApi(application, config.serverUrl)
        }
        if (api == null) {
            handleAuthFailure("Could not initialize re-login")
            return
        }

        try {
            val response = withContext(Dispatchers.IO) {
                api.login(LoginRequest(config.username, config.password))
            }
            val body = response.body()
            val token = body?.token
            if (response.isSuccessful && body?.success == true && !token.isNullOrBlank()) {
                Log.i("SratimNet", "Auto-login successful, updating token")
                configManager.updateToken(token)
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
    }

    fun logout() {
        viewModelScope.launch {
            SratimDataCache.clearAll()
            NetworkClient.invalidateCaches()
            configManager.clearConfig()
        }
    }
}
