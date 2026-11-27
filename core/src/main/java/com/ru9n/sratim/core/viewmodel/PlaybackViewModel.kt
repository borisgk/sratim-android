package com.ru9n.sratim.core.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.data.SratimDataCache
import com.ru9n.sratim.core.network.AudioTrackDto
import com.ru9n.sratim.core.network.NetworkClient
import com.ru9n.sratim.core.network.SratimApi
import com.ru9n.sratim.core.network.WatchEventRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AudioTrackInfo(
    val id: Int,
    val label: String,
    val codec: String? = null
)

data class PlaybackUiState(
    val baseStreamUrl: String? = null,
    val videoUrl: String? = null,
    val cookie: String? = null,
    val fileSize: Long = 0L,
    val durationUs: Long = 0L,
    val resumePositionMs: Long = 0L,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val selectedAudioTrackId: Int = -1,
    val audioSwitchNonce: Int = 0,
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

    private data class PlayerPageMetadata(
        val durationSec: Double? = null,
        val startPositionSec: Double? = null,
        val audioTracks: List<AudioTrackDto>? = null
    )

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    init {
        preparePlayback()
    }

    private fun preparePlayback() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val config = withContext(Dispatchers.IO) { configManager.configFlow.first() }
            if (!config.isValid) {
                _uiState.value = PlaybackUiState(error = "Not configured", isLoading = false)
                return@launch
            }

            SratimDataCache.ensureSession(config)

            try {
                val preparedState = withContext(Dispatchers.IO) {
                    val api = NetworkClient.createApi(application, config.serverUrl, config.token)
                        ?: return@withContext PlaybackUiState(error = "Failed to create API", isLoading = false)

                    val serverBaseUrl = NetworkClient.getBaseUrl(application, config.serverUrl)

                    var baseStreamUrl = ""
                    var fileSize = 0L
                    var durationUs = 0L
                    var resumeSec: Double? = null
                    var rawAudioTracks: List<AudioTrackDto>? = null
                    var playerPageMetadata: PlayerPageMetadata? = null

                    if (movieId != -1) {
                        val movie = SratimDataCache.getFreshRawMovieDetail(movieId) ?: run {
                            val response = api.getMovieDetails(movieId)
                            if (response.isSuccessful) {
                                response.body()?.movie?.also { SratimDataCache.putRawMovieDetail(movieId, it) }
                            } else {
                                Log.e("PlaybackVM", "API Error: ${response.code()} ${response.message()}")
                                null
                            }
                        }
                        if (movie != null) {
                            baseStreamUrl = "${serverBaseUrl}stream?id=$movieId"
                            fileSize = movie.fileSize ?: movie.filesize ?: 0L
                            durationUs = when {
                                movie.durationSec != null && movie.durationSec > 0.0 ->
                                    (movie.durationSec * 1_000_000.0).toLong()
                                else -> {
                                    val runtimeMins = movie.runtime ?: movie.duration ?: 0
                                    runtimeMins.toLong() * 60 * 1_000_000L
                                }
                            }
                            resumeSec = SratimDataCache.getMovieResumePositionSec(movieId)
                                ?: movie.resumePosition
                                ?: movie.position
                            rawAudioTracks = movie.audioTracks
                        }
                    } else if (episodeId != -1) {
                        baseStreamUrl = "${serverBaseUrl}stream?episode_id=$episodeId"

                        // 1. Check in-memory caches first (never call getShowDetails here, which would
                        // probe every episode of multi-season shows when launching from Recently Watched).
                        val cachedShowEpisode = if (showId != -1) {
                            SratimDataCache.getFreshRawShowDetail(showId)?.episodes?.find { it.id == episodeId }
                        } else null

                        var episode = SratimDataCache.getFreshRawEpisodeDetail(episodeId)

                        // 2. Try lightweight single-episode endpoint (/api/v1/episode?id=...) if supported
                        val needsEpisodeProbe = episode == null && (
                            cachedShowEpisode == null ||
                                ((cachedShowEpisode.durationSec ?: 0.0) <= 0.0 && (cachedShowEpisode.runtime ?: cachedShowEpisode.duration ?: 0) <= 0) ||
                                (cachedShowEpisode.audioTracks == null && SratimDataCache.getEpisodeAudioTracks(episodeId) == null)
                            )

                        if (needsEpisodeProbe && SratimDataCache.serverSupportsEpisodeEndpoint) {
                            try {
                                val epResponse = api.getEpisodeDetails(episodeId)
                                if (epResponse.isSuccessful) {
                                    epResponse.body()?.episode?.let { fetched ->
                                        SratimDataCache.putRawEpisodeDetail(episodeId, fetched)
                                        episode = fetched
                                    }
                                } else if (epResponse.code() == 404) {
                                    SratimDataCache.serverSupportsEpisodeEndpoint = false
                                }
                            } catch (e: Exception) {
                                Log.d("PlaybackVM", "Single-episode endpoint unavailable: ${e.message}")
                            }
                        }

                        val effectiveEp = episode ?: cachedShowEpisode
                        if (effectiveEp != null) {
                            fileSize = effectiveEp.fileSize ?: effectiveEp.filesize
                                ?: cachedShowEpisode?.fileSize ?: cachedShowEpisode?.filesize ?: 0L
                            durationUs = when {
                                effectiveEp.durationSec != null && effectiveEp.durationSec > 0.0 ->
                                    (effectiveEp.durationSec * 1_000_000.0).toLong()
                                else -> {
                                    val runtimeMins = effectiveEp.runtime ?: effectiveEp.duration
                                        ?: cachedShowEpisode?.runtime ?: cachedShowEpisode?.duration ?: 0
                                    runtimeMins.toLong() * 60 * 1_000_000L
                                }
                            }
                            resumeSec = SratimDataCache.getEpisodeResumePositionSec(episodeId)
                                ?: effectiveEp.resumePosition
                                ?: effectiveEp.position
                                ?: cachedShowEpisode?.resumePosition
                                ?: cachedShowEpisode?.position
                            rawAudioTracks = effectiveEp.audioTracks ?: cachedShowEpisode?.audioTracks
                        }

                        // 3. Single-episode /player fallback: probes ONLY this 1 episode on pre-upgrade servers
                        if (durationUs <= 0L || (rawAudioTracks == null && SratimDataCache.getEpisodeAudioTracks(episodeId) == null)) {
                            playerPageMetadata = fetchPlayerPageMetadata(api)
                            if (durationUs <= 0L && playerPageMetadata?.durationSec != null && playerPageMetadata.durationSec > 0.0) {
                                durationUs = (playerPageMetadata.durationSec * 1_000_000.0).toLong()
                            }
                            if ((resumeSec == null || resumeSec <= 0.0) && playerPageMetadata?.startPositionSec != null && playerPageMetadata.startPositionSec > 0.0) {
                                resumeSec = playerPageMetadata.startPositionSec
                            }
                            if (rawAudioTracks == null && playerPageMetadata?.audioTracks != null) {
                                rawAudioTracks = playerPageMetadata.audioTracks
                            }
                        }
                    }

                    if (baseStreamUrl.isEmpty()) {
                        return@withContext PlaybackUiState(
                            error = "Failed to load media metadata (Server Error 502?)",
                            isLoading = false
                        )
                    }

                    // Ensure synthetic byte-to-time mapping works for seeking even if file_size wasn't cached yet
                    if (fileSize <= 0L && durationUs > 0L) {
                        fileSize = (durationUs / 1_000_000L).coerceAtLeast(1L) * 1_000_000L
                    }

                    val cachedAudioTracks = if (movieId != -1) {
                        SratimDataCache.getMovieAudioTracks(movieId)
                    } else {
                        SratimDataCache.getEpisodeAudioTracks(episodeId)
                    }

                    val audioTracks = cachedAudioTracks ?: run {
                        val dtos = rawAudioTracks ?: (playerPageMetadata ?: fetchPlayerPageMetadata(api))?.audioTracks
                        val mapped = dtos.orEmpty().mapIndexed { index, dto ->
                            val cleanLabel = dto.label?.trim().takeUnless { it.isNullOrEmpty() } ?: "Track ${index + 1}"
                            val cleanCodec = dto.codec?.trim()?.uppercase()?.takeIf {
                                it.isNotEmpty() && !it.equals("UNKNOWN", ignoreCase = true)
                            }
                            AudioTrackInfo(
                                id = dto.id,
                                label = cleanLabel,
                                codec = cleanCodec
                            )
                        }
                        if (movieId != -1) {
                            SratimDataCache.putMovieAudioTracks(movieId, mapped)
                        } else if (episodeId != -1) {
                            SratimDataCache.putEpisodeAudioTracks(episodeId, mapped)
                        }
                        mapped
                    }

                    val initialAudioTrackId = audioTracks.firstOrNull()?.id ?: -1

                    // Fallback: check cached recentlyWatched first, then network if needed
                    if (resumeSec == null || resumeSec <= 0.0) {
                        val cachedRecent = SratimDataCache.getRecentlyWatched()
                        if (cachedRecent != null) {
                            val cachedMatch = if (movieId != -1) {
                                cachedRecent.find { it.type == "movie" && it.id == movieId }
                            } else {
                                cachedRecent.find { it.type == "episode" && it.id == episodeId }
                            }
                            if (cachedMatch != null && cachedMatch.positionSeconds > 0.0) {
                                resumeSec = cachedMatch.positionSeconds
                            }
                        } else {
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
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d("PlaybackVM", "Fallback check for resume failed: ${e.message}")
                            }
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

                    PlaybackUiState(
                        baseStreamUrl = baseStreamUrl,
                        videoUrl = baseStreamUrl,
                        cookie = "session=${config.token}",
                        fileSize = fileSize,
                        durationUs = durationUs,
                        resumePositionMs = resumePositionMs,
                        audioTracks = audioTracks,
                        selectedAudioTrackId = initialAudioTrackId,
                        isLoading = false
                    )
                }

                _uiState.value = preparedState
            } catch (e: Exception) {
                Log.e("PlaybackVM", "Playback Error", e)
                _uiState.value = PlaybackUiState(error = e.message, isLoading = false)
            }
        }
    }

    private suspend fun fetchPlayerPageMetadata(api: SratimApi): PlayerPageMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                val response = when {
                    movieId != -1 -> api.getPlayerHtml(movieId = movieId)
                    episodeId != -1 -> api.getPlayerHtml(episodeId = episodeId)
                    else -> null
                } ?: return@withContext null

                if (!response.isSuccessful) return@withContext null
                val html = response.body()?.use { it.string() } ?: return@withContext null

                val durationSec = DURATION_REGEX.find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                val startPositionSec = START_POSITION_REGEX.find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                val tracksJson = AUDIO_TRACKS_REGEX.find(html)?.groupValues?.getOrNull(1)
                val audioTracks = tracksJson?.let {
                    NetworkClient.sharedGson.fromJson(it, Array<AudioTrackDto>::class.java)?.toList()
                }

                Log.d("PlaybackVM", "Parsed /player metadata: duration=${durationSec}s, tracks=${audioTracks?.size ?: 0}")
                PlayerPageMetadata(
                    durationSec = durationSec,
                    startPositionSec = startPositionSec,
                    audioTracks = audioTracks
                )
            } catch (e: Exception) {
                Log.d("PlaybackVM", "Player metadata fallback skipped: ${e.message}")
                null
            }
        }
    }

    fun selectAudioTrack(trackId: Int, currentPositionMs: Long) {
        val current = _uiState.value
        val base = current.baseStreamUrl ?: return
        if (current.selectedAudioTrackId == trackId) return

        val safePositionMs = currentPositionMs.coerceAtLeast(0L)
        val separator = if (base.contains("?")) "&" else "?"
        val newUrl = "${base}${separator}audio=$trackId"

        Log.i("PlaybackVM", "Switching audio track to id=$trackId at ${safePositionMs / 1000}s -> $newUrl")

        _uiState.value = current.copy(
            videoUrl = newUrl,
            selectedAudioTrackId = trackId,
            resumePositionMs = safePositionMs,
            audioSwitchNonce = current.audioSwitchNonce + 1
        )
    }

    private var lastReportedEvent: String? = null
    private var lastReportedPositionSec: Double = -100.0
    private var lastReportedTimeMs: Long = 0L

    fun reportWatchProgress(positionMs: Long, durationMs: Long, event: String = "progress") {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                if (positionMs < 0) return@withContext
                val positionSec = (positionMs / 1000.0).coerceAtLeast(0.0)
                val now = System.currentTimeMillis()

                // Deduplicate rapid or redundant events:
                // 1. If progress event arrives less than 8 seconds after the last report, skip
                if (event == "progress" && Math.abs(positionSec - lastReportedPositionSec) < 8.0 && (now - lastReportedTimeMs) < 8_000) {
                    return@withContext
                }
                // 2. If the same event arrives within 2 seconds at the same position, skip
                if (event == lastReportedEvent && Math.abs(positionSec - lastReportedPositionSec) < 1.0 && (now - lastReportedTimeMs) < 2_000) {
                    return@withContext
                }

                lastReportedEvent = event
                lastReportedPositionSec = positionSec
                lastReportedTimeMs = now

                val durationSec = if (durationMs > 0) {
                    durationMs / 1000.0
                } else if (_uiState.value.durationUs > 0) {
                    _uiState.value.durationUs / 1_000_000.0
                } else {
                    0.0
                }

                // Propagate immediately to in-memory cache so Back -> Play or Home is instant
                SratimDataCache.recordWatchProgress(
                    movieId = movieId,
                    episodeId = episodeId,
                    positionSec = positionSec,
                    durationSec = durationSec
                )

                val config = configManager.configFlow.first()
                if (!config.isValid) return@withContext

                val api = NetworkClient.createApi(application, config.serverUrl, config.token) ?: return@withContext
                try {
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

    companion object {
        private val DURATION_REGEX = Regex("""const\s+DURATION\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*;""")
        private val START_POSITION_REGEX = Regex("""const\s+START_POSITION\s*=\s*(-?[0-9]+(?:\.[0-9]+)?)\s*;""")
        private val AUDIO_TRACKS_REGEX = Regex("""const\s+AUDIO_TRACKS\s*=\s*(\[[\s\S]*?]);""")
    }
}
