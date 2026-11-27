package com.ru9n.sratim.core.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ru9n.sratim.core.data.Config
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.data.SratimDataCache
import com.ru9n.sratim.core.network.LoginRequest
import com.ru9n.sratim.core.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetupUiState(
    val currentStep: Int = 0,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: ConnectionResult? = null,
    val isComplete: Boolean = false
)

sealed class ConnectionResult {
    object Success : ConnectionResult()
    data class Error(val message: String) : ConnectionResult()
}

class SetupViewModel(
    private val application: Application,
    private val configManager: ConfigManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) { configManager.configFlow.first() }
            if (existing.serverUrl.isNotBlank() || existing.username.isNotBlank()) {
                _uiState.update { current ->
                    current.copy(
                        serverUrl = current.serverUrl.ifBlank { existing.serverUrl },
                        username = current.username.ifBlank { existing.username },
                        password = current.password.ifBlank { existing.password }
                    )
                }
            }
        }
    }

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, testResult = null) }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, testResult = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, testResult = null) }
    }

    fun nextStep() {
        if (_uiState.value.currentStep < 2) {
            _uiState.update { it.copy(currentStep = it.currentStep + 1) }
        } else {
            saveConfig()
        }
    }

    fun previousStep() {
        if (_uiState.value.currentStep > 0) {
            _uiState.update { it.copy(currentStep = it.currentStep - 1, testResult = null) }
        }
    }

    fun testConnection() {
        val serverUrl = _uiState.value.serverUrl
        val username = _uiState.value.username
        val password = _uiState.value.password

        if (serverUrl.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            val api = withContext(Dispatchers.IO) {
                NetworkClient.invalidateCaches()
                NetworkClient.createApi(application, serverUrl)
            }
            if (api == null) {
                _uiState.update { it.copy(isTesting = false, testResult = ConnectionResult.Error("Invalid URL")) }
                return@launch
            }

            try {
                val response = withContext(Dispatchers.IO) {
                    api.login(LoginRequest(username, password))
                }
                val loginResponse = response.body()

                if (response.isSuccessful && loginResponse?.success == true) {
                    val token = loginResponse.token
                    if (!token.isNullOrBlank()) {
                        _uiState.update { it.copy(isTesting = false, testResult = ConnectionResult.Success, token = token) }
                    } else {
                        _uiState.update { it.copy(isTesting = false, testResult = ConnectionResult.Error("Server returned success but no session token")) }
                    }
                } else {
                    val errorMsg = loginResponse?.error ?: loginResponse?.token?.toString() ?: "Invalid credentials"
                    Log.w("SetupViewModel", "Login failed: $errorMsg")
                    _uiState.update { it.copy(isTesting = false, testResult = ConnectionResult.Error(errorMsg)) }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isTesting = false, testResult = ConnectionResult.Error("Connection failed")) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            SratimDataCache.clearAll()
            NetworkClient.invalidateCaches()
            configManager.clearConfig()
        }
    }

    private fun saveConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val config = Config(
                serverUrl = _uiState.value.serverUrl,
                username = _uiState.value.username,
                password = _uiState.value.password,
                token = _uiState.value.token
            )
            SratimDataCache.ensureSession(config)
            NetworkClient.invalidateCaches()
            configManager.saveConfig(config)
            _uiState.update { it.copy(isSaving = false, isComplete = true) }
        }
    }
}
