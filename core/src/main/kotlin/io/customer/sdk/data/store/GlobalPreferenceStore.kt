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
    fun saveUserId(token: String)
    fun getUserId(): String?
    fun removeDeviceToken()
    fun removeUserId()
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

    override fun getDeviceToken(): String? = prefs.read {
        getString(KEY_DEVICE_TOKEN, null)
    }

    override fun saveUserId(token: String) = prefs.edit {
        putString(KEY_USER_ID, token)
    }

    override fun getUserId(): String? = prefs.read {
        getString(KEY_USER_ID, null)
    }

    override fun removeDeviceToken() = clear(KEY_DEVICE_TOKEN)

    override fun removeUserId() = clear(KEY_USER_ID)

    companion object {
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_USER_ID = "user_id"
    }
}
