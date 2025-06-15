package io.customer.sdk.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.customer.sdk.data.model.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Store for global preferences that are not tied to a specific api key, user
 * or any other entity.
 */
interface GlobalPreferenceStore {
    suspend fun saveDeviceToken(token: String)
    suspend fun saveSettings(value: Settings)
    suspend fun getDeviceToken(): String?
    suspend fun getSettings(): Settings?
    suspend fun removeDeviceToken()
    suspend fun clear(key: String)
    suspend fun clearAll()
}

internal class GlobalPreferenceStoreImpl(
    private val context: Context
) : GlobalPreferenceStore {

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("io.customer.sdk.${context.packageName}")
        }
    }

    override suspend fun saveDeviceToken(token: String) {
        dataStore.edit { preferences ->
            preferences[KEY_DEVICE_TOKEN] = token
        }
    }

    override suspend fun saveSettings(value: Settings) {
        dataStore.edit { preferences ->
            preferences[KEY_CONFIG_SETTINGS] = Json.encodeToString(Settings.serializer(), value)
        }
    }

    override suspend fun getDeviceToken(): String? {
        return dataStore.data.map { preferences ->
            preferences[KEY_DEVICE_TOKEN]
        }.first()
    }

    override suspend fun getSettings(): Settings? {
        return dataStore.data.map { preferences ->
            preferences[KEY_CONFIG_SETTINGS]?.let { settingsJson ->
                runCatching {
                    Json.decodeFromString(Settings.serializer(), settingsJson)
                }.getOrNull()
            }
        }.first()
    }

    override suspend fun removeDeviceToken() = clear(KEY_DEVICE_TOKEN.name)

    override suspend fun clear(key: String) {
        dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(key))
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    companion object {
        private val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token")
        private val KEY_CONFIG_SETTINGS = stringPreferencesKey("config_settings")
    }
}
