package com.ru9n.sratim.core.data

import com.ru9n.sratim.core.network.Episode
import com.ru9n.sratim.core.network.Library
import com.ru9n.sratim.core.network.MovieDetail
import com.ru9n.sratim.core.network.ShowDetail
import com.ru9n.sratim.core.viewmodel.AudioTrackInfo
import com.ru9n.sratim.core.viewmodel.LibraryItemUiModel
import com.ru9n.sratim.core.viewmodel.MovieDetailUiModel
import com.ru9n.sratim.core.viewmodel.RecentlyWatchedUiModel
import com.ru9n.sratim.core.viewmodel.ShowDetailUiModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, session-scoped in-memory cache enabling instant (`0ms`) UI hydration
 * when navigating across Home, Libraries, Detail screens, and Playback, paired with
 * background stale-while-revalidate refreshes.
 */
object SratimDataCache {
    private const val METADATA_FRESH_TTL_MS = 120_000L // 2 minutes

    data class Timestamped<T>(
        val value: T,
        val updatedAtMs: Long = System.currentTimeMillis()
    ) {
        fun isFresh(ttlMs: Long = METADATA_FRESH_TTL_MS): Boolean {
            return System.currentTimeMillis() - updatedAtMs <= ttlMs
        }
    }

    @Volatile
    private var activeSessionKey: String = ""

    @Volatile
    private var libraries: List<Library>? = null

    @Volatile
    private var recentlyWatched: List<RecentlyWatchedUiModel>? = null

    private val recentlyWatchedDirty = AtomicBoolean(false)

    private val libraryItems = ConcurrentHashMap<Int, List<LibraryItemUiModel>>()
    private val movieUiModels = ConcurrentHashMap<Int, MovieDetailUiModel>()
    private val showUiModels = ConcurrentHashMap<Int, ShowDetailUiModel>()

    private val rawMovieDetails = ConcurrentHashMap<Int, Timestamped<MovieDetail>>()
    private val rawShowDetails = ConcurrentHashMap<Int, Timestamped<ShowDetail>>()
    private val rawEpisodeDetails = ConcurrentHashMap<Int, Timestamped<Episode>>()

    @Volatile
    var serverSupportsEpisodeEndpoint: Boolean = true

    private val movieAudioTracks = ConcurrentHashMap<Int, List<AudioTrackInfo>>()
    private val episodeAudioTracks = ConcurrentHashMap<Int, List<AudioTrackInfo>>()

    private val movieResumePositionsSec = ConcurrentHashMap<Int, Double>()
    private val episodeResumePositionsSec = ConcurrentHashMap<Int, Double>()

    fun ensureSession(config: Config) {
        val key = "${config.serverUrl.trim()}|${config.username.trim()}"
        if (activeSessionKey != key) {
            synchronized(this) {
                if (activeSessionKey != key) {
                    clearAll()
                    activeSessionKey = key
                }
            }
        }
    }

    fun clearAll() {
        libraries = null
        recentlyWatched = null
        recentlyWatchedDirty.set(false)
        serverSupportsEpisodeEndpoint = true
        libraryItems.clear()
        movieUiModels.clear()
        showUiModels.clear()
        rawMovieDetails.clear()
        rawShowDetails.clear()
        rawEpisodeDetails.clear()
        movieAudioTracks.clear()
        episodeAudioTracks.clear()
        movieResumePositionsSec.clear()
        episodeResumePositionsSec.clear()
    }

    fun getLibraries(): List<Library>? = libraries

    fun putLibraries(items: List<Library>) {
        libraries = items
    }

    fun getRecentlyWatched(): List<RecentlyWatchedUiModel>? = recentlyWatched

    fun putRecentlyWatched(items: List<RecentlyWatchedUiModel>) {
        recentlyWatched = items
        recentlyWatchedDirty.set(false)
        for (item in items) {
            if (item.positionSeconds > 0.0) {
                if (item.type == "movie") {
                    movieResumePositionsSec[item.id] = item.positionSeconds
                } else if (item.type == "episode") {
                    episodeResumePositionsSec[item.id] = item.positionSeconds
                }
            }
        }
    }

    fun consumeRecentlyWatchedDirty(): Boolean = recentlyWatchedDirty.getAndSet(false)

    fun getLibraryItems(libraryId: Int): List<LibraryItemUiModel>? = libraryItems[libraryId]

    fun putLibraryItems(libraryId: Int, items: List<LibraryItemUiModel>) {
        libraryItems[libraryId] = items
    }

    fun getMovieUiModel(movieId: Int): MovieDetailUiModel? = movieUiModels[movieId]

    fun putMovieDetail(movieId: Int, raw: MovieDetail, uiModel: MovieDetailUiModel) {
        rawMovieDetails[movieId] = Timestamped(raw)
        movieUiModels[movieId] = uiModel
        val pos = raw.resumePosition ?: raw.position
        if (pos != null && pos > 0.0) {
            movieResumePositionsSec[movieId] = pos
        }
    }

    fun getFreshRawMovieDetail(movieId: Int): MovieDetail? {
        return rawMovieDetails[movieId]?.takeIf { it.isFresh() }?.value
    }

    fun putRawMovieDetail(movieId: Int, raw: MovieDetail) {
        rawMovieDetails[movieId] = Timestamped(raw)
        val pos = raw.resumePosition ?: raw.position
        if (pos != null && pos > 0.0) {
            movieResumePositionsSec[movieId] = pos
        }
    }

    fun getShowUiModel(showId: Int): ShowDetailUiModel? = showUiModels[showId]

    fun putShowDetail(showId: Int, raw: ShowDetail, uiModel: ShowDetailUiModel) {
        rawShowDetails[showId] = Timestamped(raw)
        showUiModels[showId] = uiModel
        raw.episodes?.forEach { ep ->
            val pos = ep.resumePosition ?: ep.position
            if (pos != null && pos > 0.0) {
                episodeResumePositionsSec[ep.id] = pos
            }
        }
    }

    fun getFreshRawShowDetail(showId: Int): ShowDetail? {
        return rawShowDetails[showId]?.takeIf { it.isFresh() }?.value
    }

    fun putRawShowDetail(showId: Int, raw: ShowDetail) {
        rawShowDetails[showId] = Timestamped(raw)
        raw.episodes?.forEach { ep ->
            val pos = ep.resumePosition ?: ep.position
            if (pos != null && pos > 0.0) {
                episodeResumePositionsSec[ep.id] = pos
            }
        }
    }

    fun getFreshRawEpisodeDetail(episodeId: Int): Episode? {
        return rawEpisodeDetails[episodeId]?.takeIf { it.isFresh() }?.value
    }

    fun putRawEpisodeDetail(episodeId: Int, raw: Episode) {
        rawEpisodeDetails[episodeId] = Timestamped(raw)
        val pos = raw.resumePosition ?: raw.position
        if (pos != null && pos > 0.0) {
            episodeResumePositionsSec[episodeId] = pos
        }
    }

    fun getMovieAudioTracks(movieId: Int): List<AudioTrackInfo>? = movieAudioTracks[movieId]

    fun putMovieAudioTracks(movieId: Int, tracks: List<AudioTrackInfo>) {
        movieAudioTracks[movieId] = tracks
    }

    fun getEpisodeAudioTracks(episodeId: Int): List<AudioTrackInfo>? = episodeAudioTracks[episodeId]

    fun putEpisodeAudioTracks(episodeId: Int, tracks: List<AudioTrackInfo>) {
        episodeAudioTracks[episodeId] = tracks
    }

    fun getMovieResumePositionSec(movieId: Int): Double? = movieResumePositionsSec[movieId]

    fun getEpisodeResumePositionSec(episodeId: Int): Double? = episodeResumePositionsSec[episodeId]

    fun recordWatchProgress(
        movieId: Int = -1,
        episodeId: Int = -1,
        positionSec: Double,
        durationSec: Double
    ) {
        if (movieId != -1) {
            movieResumePositionsSec[movieId] = positionSec
            rawMovieDetails[movieId]?.let { entry ->
                rawMovieDetails[movieId] = Timestamped(
                    entry.value.copy(resumePosition = positionSec, position = positionSec),
                    entry.updatedAtMs
                )
            }
        } else if (episodeId != -1) {
            episodeResumePositionsSec[episodeId] = positionSec
        }

        // Update in-memory recentlyWatched progress immediately and mark dirty for silent refresh
        recentlyWatched?.let { currentList ->
            val targetType = if (movieId != -1) "movie" else "episode"
            val targetId = if (movieId != -1) movieId else episodeId
            val idx = currentList.indexOfFirst { it.type == targetType && it.id == targetId }
            if (idx != -1) {
                val existing = currentList[idx]
                val effectiveDur = if (durationSec > 0.0) durationSec else existing.durationSeconds
                val pct = if (effectiveDur > 0.0) {
                    ((positionSec / effectiveDur) * 100.0).toFloat().coerceIn(0f, 100f)
                } else existing.progressPercent
                val updatedItem = existing.copy(
                    positionSeconds = positionSec,
                    durationSeconds = effectiveDur,
                    progressPercent = pct
                )
                val reordered = buildList(currentList.size) {
                    add(updatedItem)
                    currentList.forEachIndexed { i, item ->
                        if (i != idx) add(item)
                    }
                }
                recentlyWatched = reordered
            }
        }
        recentlyWatchedDirty.set(true)
    }
}
