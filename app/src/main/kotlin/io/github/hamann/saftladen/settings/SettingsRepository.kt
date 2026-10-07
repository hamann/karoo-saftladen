package io.github.hamann.saftladen.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Everything the rider can configure. */
data class SaftladenSettings(
    val enabled: Boolean = true,
    /** Endpoint that reports are POSTed to. Reports are still buffered while this is blank. */
    val endpointUrl: String = "",
    /** Optional extra request header, e.g. `Authorization` / `Bearer …`. */
    val authHeaderName: String = "",
    val authHeaderValue: String = "",
    val includeSerialNumbers: Boolean = true,
) {
    val isConfigured: Boolean
        get() = endpointUrl.isNotBlank()

    /** Headers to send with the report, including the fixed content type. */
    fun requestHeaders(): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (authHeaderName.isNotBlank() && authHeaderValue.isNotBlank()) {
            put(authHeaderName.trim(), authHeaderValue.trim())
        }
    }
}

/** Result of the most recent upload attempt, shown in the UI. */
data class DeliveryStatus(
    val lastAttemptAt: Instant? = null,
    val lastMessage: String = "",
    val lastSuccessAt: Instant? = null,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "saftladen")

class SettingsRepository(private val context: Context) {
    val settings: Flow<SaftladenSettings> = context.dataStore.data.map { prefs ->
        SaftladenSettings(
            enabled = prefs[KEY_ENABLED] ?: true,
            endpointUrl = prefs[KEY_URL] ?: "",
            authHeaderName = prefs[KEY_AUTH_NAME] ?: "",
            authHeaderValue = prefs[KEY_AUTH_VALUE] ?: "",
            includeSerialNumbers = prefs[KEY_INCLUDE_SERIALS] ?: true,
        )
    }

    val status: Flow<DeliveryStatus> = context.dataStore.data.map { prefs ->
        DeliveryStatus(
            lastAttemptAt = prefs[KEY_LAST_ATTEMPT]?.let(Instant::ofEpochMilli),
            lastMessage = prefs[KEY_LAST_MESSAGE] ?: "",
            lastSuccessAt = prefs[KEY_LAST_SUCCESS]?.let(Instant::ofEpochMilli),
        )
    }

    suspend fun current(): SaftladenSettings = settings.first()

    suspend fun save(settings: SaftladenSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ENABLED] = settings.enabled
            prefs[KEY_URL] = settings.endpointUrl.trim()
            prefs[KEY_AUTH_NAME] = settings.authHeaderName.trim()
            prefs[KEY_AUTH_VALUE] = settings.authHeaderValue.trim()
            prefs[KEY_INCLUDE_SERIALS] = settings.includeSerialNumbers
        }
    }

    suspend fun recordAttempt(message: String, succeeded: Boolean, at: Instant = Instant.now()) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_ATTEMPT] = at.toEpochMilli()
            prefs[KEY_LAST_MESSAGE] = message
            if (succeeded) prefs[KEY_LAST_SUCCESS] = at.toEpochMilli()
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_URL = stringPreferencesKey("endpoint_url")
        val KEY_AUTH_NAME = stringPreferencesKey("auth_header_name")
        val KEY_AUTH_VALUE = stringPreferencesKey("auth_header_value")
        val KEY_INCLUDE_SERIALS = booleanPreferencesKey("include_serial_numbers")
        val KEY_LAST_ATTEMPT = longPreferencesKey("last_attempt_at")
        val KEY_LAST_MESSAGE = stringPreferencesKey("last_message")
        val KEY_LAST_SUCCESS = longPreferencesKey("last_success_at")
    }
}
