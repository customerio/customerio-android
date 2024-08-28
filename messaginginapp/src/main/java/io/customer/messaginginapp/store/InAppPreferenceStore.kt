package io.customer.messaginginapp.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

interface InAppPreferenceStore {
    fun saveNetworkResponse(url: String, response: String)
    fun getNetworkResponse(url: String): String?
    fun clearAll()
}

internal class InAppPreferenceStoreImpl(
    context: Context
) : PreferenceStore(context), InAppPreferenceStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.inApp.${context.packageName}"
    }

    override fun saveNetworkResponse(url: String, response: String) = prefs.edit {
        putString(url, response)
    }

    override fun getNetworkResponse(url: String): String? = prefs.read {
        getString(url, null)
    }
}
