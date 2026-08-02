package com.ru9n.sratim.core.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.network.LibraryItem
import com.ru9n.sratim.core.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = false,
    val items: List<LibraryItemUiModel> = emptyList(),
    val error: String? = null
)

data class LibraryItemUiModel(
    val id: Int,
    val title: String,
    val posterUrl: String,
    val type: String
)

class LibraryViewModel(
    private val application: Application,
    private val configManager: ConfigManager,
    private val libraryId: Int
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        fetchLibraryItems()
    }

    fun fetchLibraryItems() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            val config = configManager.configFlow.first()
            val api = NetworkClient.createApi(application, config.serverUrl, config.token)
            
            if (api == null) {
                _uiState.update { it.copy(isLoading = false, error = "Network error") }
                return@launch
            }

            try {
                val response = api.getLibraryItems(libraryId)
                val body = response.body()
                val items = body?.items
                
                if (response.isSuccessful && body?.success == true) {
                    val serverBaseUrl = NetworkClient.getBaseUrl(application, config.serverUrl)
                    val uiModels = items?.map { item ->
                        val posterUrl = if (item.posterPath.isNotBlank()) {
                            // Logic from spec Section 4
                            // serverBaseUrl already ends with /
                            val path = item.posterPath.removePrefix("/")
                            "${serverBaseUrl}images/posters/w185/$path"
                        } else ""
                        
                        Log.e("SratimNet", "Poster URL: $posterUrl")
                        
                        LibraryItemUiModel(
                            id = item.id,
                            title = item.title,
                            posterUrl = posterUrl,
                            type = item.type
                        )
                    } ?: emptyList()
                    
                    _uiState.update { it.copy(isLoading = false, items = uiModels) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = body?.error ?: "Error ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
