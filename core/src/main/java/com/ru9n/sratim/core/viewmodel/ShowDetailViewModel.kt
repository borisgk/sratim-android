package com.ru9n.sratim.core.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.data.SratimDataCache
import com.ru9n.sratim.core.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShowDetailUiState(
    val isLoading: Boolean = false,
    val show: ShowDetailUiModel? = null,
    val error: String? = null
)

data class ShowDetailUiModel(
    val id: Int,
    val title: String,
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val seasons: Map<Int, List<EpisodeUiModel>>
)

data class EpisodeUiModel(
    val id: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String,
    val stillUrl: String
)

class ShowDetailViewModel(
    private val application: Application,
    private val configManager: ConfigManager,
    private val showId: Int
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        ShowDetailUiState(show = SratimDataCache.getShowUiModel(showId))
    )
    val uiState: StateFlow<ShowDetailUiState> = _uiState.asStateFlow()

    init {
        fetchShowDetails()
    }

    fun fetchShowDetails() {
        viewModelScope.launch {
            val hasCachedShow = _uiState.value.show != null
            _uiState.update { it.copy(isLoading = !hasCachedShow, error = null) }

            val config = withContext(Dispatchers.IO) { configManager.configFlow.first() }
            SratimDataCache.ensureSession(config)

            val api = withContext(Dispatchers.IO) {
                NetworkClient.createApi(application, config.serverUrl, config.token)
            }
            if (api == null) {
                if (!hasCachedShow) {
                    _uiState.update { it.copy(isLoading = false, error = "Network error") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
                return@launch
            }

            try {
                val response = withContext(Dispatchers.IO) {
                    api.getShowDetails(showId)
                }
                val body = response.body()
                val show = body?.show

                if (response.isSuccessful && body?.success == true && show != null) {
                    val serverBaseUrl = withContext(Dispatchers.IO) {
                        NetworkClient.getBaseUrl(application, config.serverUrl)
                    }

                    val uiModel = withContext(Dispatchers.Default) {
                        val posterUrl = if (show.posterPath.isNotBlank()) {
                            "${serverBaseUrl}images/posters/w500/${show.posterPath.removePrefix("/")}"
                        } else ""

                        val backdropUrl = if (show.backdropPath.isNotBlank()) {
                            "${serverBaseUrl}images/backdrops/w1280/${show.backdropPath.removePrefix("/")}"
                        } else ""

                        val episodes = show.episodes ?: emptyList()
                        val seasonsMap = LinkedHashMap<Int, List<EpisodeUiModel>>()
                        episodes
                            .map { ep ->
                                val stillUrl = when {
                                    !ep.stillPath.isNullOrBlank() -> {
                                        "${serverBaseUrl}images/backdrops/w300/${ep.stillPath.removePrefix("/")}"
                                    }
                                    !ep.posterPath.isNullOrBlank() -> {
                                        "${serverBaseUrl}images/posters/w185/${ep.posterPath.removePrefix("/")}"
                                    }
                                    else -> ""
                                }

                                EpisodeUiModel(
                                    id = ep.id,
                                    season = ep.season,
                                    episode = ep.episode,
                                    title = ep.title,
                                    overview = ep.overview,
                                    stillUrl = stillUrl
                                )
                            }
                            .groupBy { it.season }
                            .toSortedMap()
                            .forEach { (season, epList) ->
                                seasonsMap[season] = epList.sortedBy { it.episode }
                            }

                        ShowDetailUiModel(
                            id = show.id,
                            title = show.title,
                            overview = show.overview,
                            posterUrl = posterUrl,
                            backdropUrl = backdropUrl,
                            seasons = seasonsMap
                        )
                    }

                    SratimDataCache.putShowDetail(showId, show, uiModel)
                    _uiState.update { it.copy(isLoading = false, show = uiModel, error = null) }
                } else if (!hasCachedShow) {
                    _uiState.update { it.copy(isLoading = false, error = body?.error ?: "Error ${response.code()}") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                if (!hasCachedShow) {
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }
}
