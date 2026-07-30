package com.example.sratim.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "config")

class ConfigManager(private val context: Context) {

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val TOKEN = stringPreferencesKey("token")
    }

    val configFlow: Flow<Config> = context.dataStore.data
        .map { preferences ->
            Config(
                serverUrl = preferences[SERVER_URL] ?: "",
                username = preferences[USERNAME] ?: "",
                password = preferences[PASSWORD] ?: "",
                token = preferences[TOKEN] ?: ""
            )
        }

    suspend fun saveConfig(config: Config) {
        Log.d("ConfigManager", "Saving config. Token length: ${config.token.length}")
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL] = config.serverUrl
            preferences[USERNAME] = config.username
            preferences[PASSWORD] = config.password
            preferences[TOKEN] = config.token
        }
    }

    suspend fun updateToken(newToken: String) {
        Log.d("ConfigManager", "Updating session token")
        context.dataStore.edit { preferences ->
            preferences[TOKEN] = newToken
        }
    }

    suspend fun clearConfig() {
        context.dataStore.edit { it.clear() }
    }
}
