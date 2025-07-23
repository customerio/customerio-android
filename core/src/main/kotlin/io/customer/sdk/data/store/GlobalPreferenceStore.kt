package io.customer.sdk.data.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Store for global preferences that are not tied to a specific api key, user
 * or any other entity.
 */
interface GlobalPreferenceStore {
    fun saveDeviceToken(token: String)
    fun saveSettings(value: Settings)
    fun getDeviceToken(): String?
    fun getSettings(): Settings?
    fun removeDeviceToken()
    fun clear(key: String)
    fun clearAll()
}

internal class GlobalPreferenceStoreImpl(
    context: Context
) : PreferenceStore(context), GlobalPreferenceStore {

    val scope: CoroutineScope = SDKComponent.scopeProvider.globalPreferenceStoreScope

    override val prefsName: String by lazy {
        "io.customer.sdk.${context.packageName}"
    }

    override fun saveDeviceToken(token: String) = prefs.edit {
        putString(KEY_DEVICE_TOKEN, token)
    }

    override fun saveSettings(value: Settings) {
        scope.launch {
            prefs.edit {
                putString(KEY_CONFIG_SETTINGS, Json.encodeToString(Settings.serializer(), value))
            }
        }
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

    override fun removeDeviceToken() = clear(KEY_DEVICE_TOKEN)

    companion object {
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_CONFIG_SETTINGS = "config_settings"
    }
}
