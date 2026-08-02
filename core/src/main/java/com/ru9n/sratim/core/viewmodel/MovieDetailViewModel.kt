package com.ru9n.sratim.core.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MovieDetailUiState(
    val isLoading: Boolean = false,
    val movie: MovieDetailUiModel? = null,
    val error: String? = null
)

data class MovieDetailUiModel(
    val id: Int,
    val title: String,
    val overview: String,
    val releaseDate: String,
    val posterUrl: String,
    val backdropUrl: String
)

class MovieDetailViewModel(
    private val application: Application,
    private val configManager: ConfigManager,
    private val movieId: Int
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    init {
        fetchMovieDetails()
    }

    fun fetchMovieDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            val config = configManager.configFlow.first()
            val api = NetworkClient.createApi(application, config.serverUrl, config.token)
            
            if (api == null) {
                _uiState.update { it.copy(isLoading = false, error = "Network error") }
                return@launch
            }

            try {
                val response = api.getMovieDetails(movieId)
                val body = response.body()
                val movie = body?.movie
                
                if (response.isSuccessful && body?.success == true && movie != null) {
                    val serverBaseUrl = NetworkClient.getBaseUrl(application, config.serverUrl)
                    
                    val posterUrl = if (movie.posterPath.isNotBlank()) {
                        "${serverBaseUrl}images/posters/w185/${movie.posterPath.removePrefix("/")}"
                    } else ""
                    
                    val backdropUrl = if (movie.backdropPath.isNotBlank()) {
                        // Correct path is backdrops
                        val path = movie.backdropPath.removePrefix("/")
                        "${serverBaseUrl}images/backdrops/original/$path"
                    } else ""
                    
                    Log.e("SratimNet", "Detail Poster: $posterUrl")
                    Log.e("SratimNet", "Detail Backdrop: $backdropUrl")
                    
                    val uiModel = MovieDetailUiModel(
                        id = movie.id,
                        title = movie.title,
                        overview = movie.overview,
                        releaseDate = movie.releaseDate,
                        posterUrl = posterUrl,
                        backdropUrl = backdropUrl
                    )
                    
                    _uiState.update { it.copy(isLoading = false, movie = uiModel) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = body?.error ?: "Error ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
