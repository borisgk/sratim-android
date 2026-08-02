package com.ru9n.sratim.core.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.network.Episode
import com.ru9n.sratim.core.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private val _uiState = MutableStateFlow(ShowDetailUiState())
    val uiState: StateFlow<ShowDetailUiState> = _uiState.asStateFlow()

    init {
        fetchShowDetails()
    }

    fun fetchShowDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            val config = configManager.configFlow.first()
            val api = NetworkClient.createApi(application, config.serverUrl, config.token)
            
            if (api == null) {
                _uiState.update { it.copy(isLoading = false, error = "Network error") }
                return@launch
            }

            try {
                val response = api.getShowDetails(showId)
                val body = response.body()
                val show = body?.show
                
                if (response.isSuccessful && body?.success == true && show != null) {
                    val serverBaseUrl = NetworkClient.getBaseUrl(application, config.serverUrl)
                    
                    val posterUrl = if (show.posterPath.isNotBlank()) {
                        "${serverBaseUrl}images/posters/w185/${show.posterPath.removePrefix("/")}"
                    } else ""
                    
                    val backdropUrl = if (show.backdropPath.isNotBlank()) {
                        "${serverBaseUrl}images/backdrops/original/${show.backdropPath.removePrefix("/")}"
                    } else ""
                    
                    val episodes = show.episodes ?: emptyList()
                    val seasonsMap = episodes.map { ep ->
                        // Determine which image to use and which path template
                        val stillUrl = when {
                            !ep.stillPath.isNullOrBlank() -> {
                                val path = ep.stillPath.removePrefix("/")
                                // Correction: episode stills use backdrops/original
                                "${serverBaseUrl}images/backdrops/original/$path"
                            }
                            !ep.posterPath.isNullOrBlank() -> {
                                val path = ep.posterPath.removePrefix("/")
                                // Assuming episode posters also follow high-res backdrop path
                                "${serverBaseUrl}images/backdrops/original/$path"
                            }
                            else -> ""
                        }
                        
                        if (stillUrl.isNotEmpty()) {
                            Log.e("SratimNet", "Episode ${ep.episode} Image URL: $stillUrl")
                        }
                        
                        EpisodeUiModel(
                            id = ep.id,
                            season = ep.season,
                            episode = ep.episode,
                            title = ep.title,
                            overview = ep.overview,
                            stillUrl = stillUrl
                        )
                    }.groupBy { it.season }
                    
                    val uiModel = ShowDetailUiModel(
                        id = show.id,
                        title = show.title,
                        overview = show.overview,
                        posterUrl = posterUrl,
                        backdropUrl = backdropUrl,
                        seasons = seasonsMap
                    )
                    
                    _uiState.update { it.copy(isLoading = false, show = uiModel) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = body?.error ?: "Error ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
