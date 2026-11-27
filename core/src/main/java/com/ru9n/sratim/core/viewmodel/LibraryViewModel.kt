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

    private val _uiState = MutableStateFlow(
        LibraryUiState(items = SratimDataCache.getLibraryItems(libraryId).orEmpty())
    )
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        fetchLibraryItems()
    }

    fun fetchLibraryItems() {
        viewModelScope.launch {
            val hasCachedItems = _uiState.value.items.isNotEmpty()
            _uiState.update { it.copy(isLoading = !hasCachedItems, error = null) }

            val config = withContext(Dispatchers.IO) { configManager.configFlow.first() }
            SratimDataCache.ensureSession(config)

            val api = withContext(Dispatchers.IO) {
                NetworkClient.createApi(application, config.serverUrl, config.token)
            }
            if (api == null) {
                if (!hasCachedItems) {
                    _uiState.update { it.copy(isLoading = false, error = "Network error") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
                return@launch
            }

            try {
                val response = withContext(Dispatchers.IO) {
                    api.getLibraryItems(libraryId)
                }
                val body = response.body()
                val items = body?.items

                if (response.isSuccessful && body?.success == true) {
                    val serverBaseUrl = withContext(Dispatchers.IO) {
                        NetworkClient.getBaseUrl(application, config.serverUrl)
                    }
                    val uiModels = withContext(Dispatchers.Default) {
                        items?.map { item ->
                            val posterUrl = if (item.posterPath.isNotBlank()) {
                                val path = item.posterPath.removePrefix("/")
                                "${serverBaseUrl}images/posters/w185/$path"
                            } else ""

                            LibraryItemUiModel(
                                id = item.id,
                                title = item.title,
                                posterUrl = posterUrl,
                                type = item.type
                            )
                        } ?: emptyList()
                    }

                    SratimDataCache.putLibraryItems(libraryId, uiModels)
                    if (_uiState.value.items != uiModels || _uiState.value.isLoading) {
                        _uiState.update { it.copy(isLoading = false, items = uiModels, error = null) }
                    }
                } else if (!hasCachedItems) {
                    _uiState.update { it.copy(isLoading = false, error = body?.error ?: "Error ${response.code()}") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                if (!hasCachedItems) {
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }
}
