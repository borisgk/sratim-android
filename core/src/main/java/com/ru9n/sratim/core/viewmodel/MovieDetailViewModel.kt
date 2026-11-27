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

    private val _uiState = MutableStateFlow(
        MovieDetailUiState(movie = SratimDataCache.getMovieUiModel(movieId))
    )
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    init {
        fetchMovieDetails()
    }

    fun fetchMovieDetails() {
        viewModelScope.launch {
            val hasCachedMovie = _uiState.value.movie != null
            _uiState.update { it.copy(isLoading = !hasCachedMovie, error = null) }

            val config = withContext(Dispatchers.IO) { configManager.configFlow.first() }
            SratimDataCache.ensureSession(config)

            val api = withContext(Dispatchers.IO) {
                NetworkClient.createApi(application, config.serverUrl, config.token)
            }
            if (api == null) {
                if (!hasCachedMovie) {
                    _uiState.update { it.copy(isLoading = false, error = "Network error") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
                return@launch
            }

            try {
                val response = withContext(Dispatchers.IO) {
                    api.getMovieDetails(movieId)
                }
                val body = response.body()
                val movie = body?.movie

                if (response.isSuccessful && body?.success == true && movie != null) {
                    val serverBaseUrl = withContext(Dispatchers.IO) {
                        NetworkClient.getBaseUrl(application, config.serverUrl)
                    }

                    val uiModel = withContext(Dispatchers.Default) {
                        val posterUrl = if (movie.posterPath.isNotBlank()) {
                            "${serverBaseUrl}images/posters/w500/${movie.posterPath.removePrefix("/")}"
                        } else ""

                        val backdropUrl = if (movie.backdropPath.isNotBlank()) {
                            "${serverBaseUrl}images/backdrops/w1280/${movie.backdropPath.removePrefix("/")}"
                        } else ""

                        MovieDetailUiModel(
                            id = movie.id,
                            title = movie.title,
                            overview = movie.overview,
                            releaseDate = movie.releaseDate,
                            posterUrl = posterUrl,
                            backdropUrl = backdropUrl
                        )
                    }

                    SratimDataCache.putMovieDetail(movieId, movie, uiModel)
                    _uiState.update { it.copy(isLoading = false, movie = uiModel, error = null) }
                } else if (!hasCachedMovie) {
                    _uiState.update { it.copy(isLoading = false, error = body?.error ?: "Error ${response.code()}") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                if (!hasCachedMovie) {
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }
}
