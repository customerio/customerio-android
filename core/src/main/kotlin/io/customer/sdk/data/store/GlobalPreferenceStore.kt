package io.customer.sdk.data.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Settings
import kotlinx.serialization.json.Json

/**
 * Store for global preferences that are not tied to a specific api key, user
 * or any other entity.
 */
interface GlobalPreferenceStore {
    fun saveDeviceToken(token: String)
    fun saveSettings(value: Settings)

    /**
     * Persists the log level the app's SDK initialization resolved, so a process the OS starts
     * for a push or a notification tap logs at that level before the app initializes the SDK.
     */
    fun saveLogLevel(level: CioLogLevel)

    fun getDeviceToken(): String?
    fun getSettings(): Settings?
    fun getLogLevel(): CioLogLevel?
    fun removeDeviceToken()
    fun clear(key: String)
    fun clearAll()
}

internal class GlobalPreferenceStoreImpl(
    context: Context
) : PreferenceStore(context), GlobalPreferenceStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.${context.packageName}"
    }

    override fun saveDeviceToken(token: String) = prefs.edit {
        putString(KEY_DEVICE_TOKEN, token)
    }

    override fun saveSettings(value: Settings) = prefs.edit {
        putString(KEY_CONFIG_SETTINGS, Json.encodeToString(Settings.serializer(), value))
    }

    // Its own key, not a field in config_settings: older SDKs decode that JSON strictly and would
    // drop the whole settings object over an unknown field after a downgrade.
    override fun saveLogLevel(level: CioLogLevel) = prefs.edit {
        putString(KEY_LOG_LEVEL, level.name)
    }

    override fun getDeviceToken(): String? = prefs.read {
        getString(KEY_DEVICE_TOKEN, null)
    }

    override fun getSettings(): Settings? = prefs.read {
        runCatching {
            Json.decodeFromString(
                Settings.serializer(),
                getString(KEY_CONFIG_SETTINGS, null) ?: return null
            )
        }.getOrNull()
    }

    override fun getLogLevel(): CioLogLevel? = prefs.read {
        val name = getString(KEY_LOG_LEVEL, null)
        CioLogLevel.values().find { level -> level.name == name }
    }

    override fun removeDeviceToken() = clear(KEY_DEVICE_TOKEN)

    companion object {
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_CONFIG_SETTINGS = "config_settings"
        private const val KEY_LOG_LEVEL = "log_level"
    }
}
