package io.customer.sdk.data.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.core.util.enumValueOfOrNull
import io.customer.sdk.data.model.DeviceTokenType
import io.customer.sdk.data.model.Settings
import kotlinx.serialization.json.Json

/**
 * Store for global preferences that are not tied to a specific api key, user
 * or any other entity.
 */
interface GlobalPreferenceStore {
    fun saveDeviceToken(token: String, type: DeviceTokenType?)
    fun getDeviceTokenType(): DeviceTokenType?
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

    override val prefsName: String by lazy {
        "io.customer.sdk.${context.packageName}"
    }

    override fun saveDeviceToken(token: String, type: DeviceTokenType?) = prefs.edit {
        putString(KEY_DEVICE_TOKEN, token)
        if (type == null) remove(KEY_DEVICE_TOKEN_TYPE) else putString(KEY_DEVICE_TOKEN_TYPE, type.name)
    }

    override fun saveSettings(value: Settings) = prefs.edit {
        putString(KEY_CONFIG_SETTINGS, Json.encodeToString(Settings.serializer(), value))
    }

    override fun getDeviceToken(): String? = prefs.read {
        getString(KEY_DEVICE_TOKEN, null)
    }

    override fun getDeviceTokenType(): DeviceTokenType? = prefs.read {
        getString(KEY_DEVICE_TOKEN_TYPE, null)?.let { enumValueOfOrNull<DeviceTokenType>(it) }
    }

    override fun getSettings(): Settings? = prefs.read {
        runCatching {
            Json.decodeFromString(
                Settings.serializer(),
                getString(KEY_CONFIG_SETTINGS, null) ?: return null
            )
        }.getOrNull()
    }

    override fun removeDeviceToken() = prefs.edit {
        remove(KEY_DEVICE_TOKEN)
        remove(KEY_DEVICE_TOKEN_TYPE)
    }

    companion object {
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_TOKEN_TYPE = "device_token_type"
        private const val KEY_CONFIG_SETTINGS = "config_settings"
    }
}
