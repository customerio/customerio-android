package io.customer.sdk.data.store

import android.content.Context
import androidx.core.content.edit

/**
 * Store for global preferences that are not tied to a specific api key, user
 * or any other entity.
 */
interface GlobalPreferenceStore {
    fun saveDeviceToken(token: String)
    fun getDeviceToken(): String?

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

    override fun getDeviceToken(): String? = prefs.read {
        getString(KEY_DEVICE_TOKEN, null)
    }

    companion object {
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}
