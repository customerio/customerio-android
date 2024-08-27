package io.customer.messaginginapp.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

interface InAppPreferenceStore {
    fun saveUserId(token: String)
    fun getUserId(): String?
    fun removeUserId()
    fun saveNetworkResponse(url: String, response: String)
    fun getNetworkResponse(url: String): String?
    fun clearAll()
}

internal class InAppPreferenceStoreImpl(
    context: Context
) : PreferenceStore(context), InAppPreferenceStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.${context.packageName}"
    }

    override fun saveUserId(token: String) = prefs.edit {
        putString(KEY_USER_ID, token)
    }

    override fun getUserId(): String? = prefs.read {
        getString(KEY_USER_ID, null)
    }

    override fun removeUserId() = clear(KEY_USER_ID)

    override fun saveNetworkResponse(url: String, response: String) = prefs.edit {
        putString(url, response)
    }

    override fun getNetworkResponse(url: String): String? = prefs.read {
        getString(url, null)
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
    }
}
