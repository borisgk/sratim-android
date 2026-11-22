package com.ru9n.sratim.core.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.network.NetworkClient
import com.ru9n.sratim.core.network.WatchEventRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val videoUrl: String? = null,
    val cookie: String? = null,
    val fileSize: Long = 0L,
    val durationUs: Long = 0L,
    val resumePositionMs: Long = 0L,
    val error: String? = null,
    val isLoading: Boolean = false
)

class PlaybackViewModel(
    private val application: Application,
    private val configManager: ConfigManager,
    private val movieId: Int = -1,
    private val showId: Int = -1,
    private val episodeId: Int = -1
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    init {
        preparePlayback()
    }

    private fun preparePlayback() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val config = configManager.configFlow.first()
            if (config.isValid) {
                try {
                    val api = NetworkClient.createApi(application, config.serverUrl, config.token)
                    if (api == null) {
                        _uiState.value = PlaybackUiState(error = "Failed to create API", isLoading = false)
                        return@launch
                    }
                    
                    val serverBaseUrl = NetworkClient.getBaseUrl(application, config.serverUrl)
                    
                    var videoUrl = ""
                    var fileSize = 0L
                    var durationUs = 0L
                    var resumeSec: Double? = null

                    if (movieId != -1) {
                        val response = api.getMovieDetails(movieId)
                        Log.d("PlaybackVM", "Movie response: ${response.body()}")
                        if (response.isSuccessful) {
                            val movie = response.body()?.movie
                            if (movie != null) {
                                videoUrl = "${serverBaseUrl}stream?id=$movieId"
                                fileSize = movie.fileSize ?: movie.filesize ?: 0L
                                val runtimeMins = movie.runtime ?: movie.duration ?: 0
                                durationUs = runtimeMins.toLong() * 60 * 1_000_000L
                                resumeSec = movie.resumePosition ?: movie.position
                                Log.d("PlaybackVM", "Movie Metadata Parsed: size=$fileSize, durationUs=$durationUs, resume=$resumeSec")
                            }
                        } else {
                            Log.e("PlaybackVM", "API Error: ${response.code()} ${response.message()}")
                        }
                    } else if (episodeId != -1 && showId != -1) {
                        val response = api.getShowDetails(showId)
                        Log.d("PlaybackVM", "Show response: ${response.body()}")
                        if (response.isSuccessful) {
                            val show = response.body()?.show
                            val episode = show?.episodes?.find { it.id == episodeId }
                            if (episode != null) {
                                videoUrl = "${serverBaseUrl}stream?episode_id=$episodeId"
                                fileSize = episode.fileSize ?: episode.filesize ?: 0L
                                val runtimeMins = episode.runtime ?: episode.duration ?: 0
                                durationUs = runtimeMins.toLong() * 60 * 1_000_000L
                                resumeSec = episode.resumePosition ?: episode.position
                                Log.d("PlaybackVM", "Episode Metadata Parsed: size=$fileSize, durationUs=$durationUs, resume=$resumeSec")
                            }
                        }
                    }

                    // Fallback: If not directly in movie/episode details, query recently watched items
                    if (resumeSec == null || resumeSec <= 0.0) {
                        try {
                            val recentResponse = api.getRecentlyWatched()
                            if (recentResponse.isSuccessful) {
                                val items = recentResponse.body()?.items.orEmpty()
                                val match = if (movieId != -1) {
                                    items.find { it.type == "movie" && it.id == movieId }
                                } else {
                                    items.find { it.type == "episode" && it.id == episodeId }
                                }
                                if (match != null && match.position != null && match.position > 0) {
                                    resumeSec = match.position
                                    Log.d("PlaybackVM", "Found resume position in recently watched: $resumeSec")
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("PlaybackVM", "Fallback check for resume failed: ${e.message}")
                        }
                    }

                    // Apply completion threshold logic matching desktop player (html.zig)
                    val effectiveDurationSec = if (durationUs > 0) durationUs / 1_000_000.0 else 0.0
                    val finalResumeSec = if (resumeSec != null && resumeSec > 0.0) {
                        if (effectiveDurationSec > 0 && (resumeSec >= effectiveDurationSec - 10.0 || resumeSec >= effectiveDurationSec * 0.95)) {
                            0.0 // Finished
                        } else if (resumeSec < 5.0) {
                            0.0 // Too close to beginning
                        } else {
                            resumeSec
                        }
                    } else {
                        0.0
                    }
                    val resumePositionMs = (finalResumeSec * 1000.0).toLong()

                    if (videoUrl.isNotEmpty()) {
                        _uiState.value = PlaybackUiState(
                            videoUrl = videoUrl,
                            cookie = "session=${config.token}",
                            fileSize = fileSize,
                            durationUs = durationUs,
                            resumePositionMs = resumePositionMs,
                            isLoading = false
                        )
                    } else {
                        _uiState.value = PlaybackUiState(error = "Failed to load media metadata (Server Error 502?)", isLoading = false)
                    }
                } catch (e: Exception) {
                    Log.e("PlaybackVM", "Playback Error", e)
                    _uiState.value = PlaybackUiState(error = e.message, isLoading = false)
                }
            } else {
                _uiState.value = PlaybackUiState(error = "Not configured", isLoading = false)
            }
        }
    }

    fun reportWatchProgress(positionMs: Long, durationMs: Long, event: String = "progress") {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                if (positionMs < 0) return@withContext
                val config = configManager.configFlow.first()
                if (!config.isValid) return@withContext

                val api = NetworkClient.createApi(application, config.serverUrl, config.token) ?: return@withContext
                try {
                    val positionSec = (positionMs / 1000.0).coerceAtLeast(0.0)
                    val durationSec = if (durationMs > 0) {
                        durationMs / 1000.0
                    } else if (_uiState.value.durationUs > 0) {
                        _uiState.value.durationUs / 1_000_000.0
                    } else {
                        0.0
                    }
                    val request = if (movieId != -1) {
                        WatchEventRequest(movieId = movieId, event = event, position = positionSec, duration = durationSec)
                    } else if (episodeId != -1) {
                        WatchEventRequest(episodeId = episodeId, event = event, position = positionSec, duration = durationSec)
                    } else null

                    if (request != null) {
                        Log.d("PlaybackVM", "Reporting watch event: $event at $positionSec / $durationSec")
                        api.postWatchEvent(request)
                    }
                } catch (e: Exception) {
                    Log.d("PlaybackVM", "Watch event report skipped: ${e.message}")
                }
            }
        }
    }
}
