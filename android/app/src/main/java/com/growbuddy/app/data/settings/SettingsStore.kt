package com.growbuddy.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Server connection the app talks to. The token never leaves DataStore and request headers. */
data class ServerConfig(val baseUrl: String, val token: String) {
    /** Retrofit base URL, always ending in `/api/v1/`. */
    val apiUrl: String get() = baseUrl.trimEnd('/') + "/api/v1/"
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("api_token")
    private val scheduleKey = longPreferencesKey("feeding_schedule_id")

    /** Emits null until a server has been configured. */
    val config: Flow<ServerConfig?> = context.dataStore.data.map { prefs ->
        val url = prefs[serverUrlKey]
        val token = prefs[tokenKey]
        if (url.isNullOrBlank() || token.isNullOrBlank()) null else ServerConfig(url, token)
    }

    suspend fun save(config: ServerConfig) {
        context.dataStore.edit {
            it[serverUrlKey] = config.baseUrl.trimEnd('/')
            it[tokenKey] = config.token
        }
    }

    /** The feeding schedule chosen on the schedule screen (used by the log wizard), or null. */
    val scheduleId: Flow<Long?> = context.dataStore.data.map { it[scheduleKey] }

    suspend fun saveScheduleId(id: Long) {
        context.dataStore.edit { it[scheduleKey] = id }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        /** Normalises user input: adds http:// when no scheme is given, drops trailing slashes. */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
