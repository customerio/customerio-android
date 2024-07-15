package io.customer.tracking.migration.repository.preference

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

interface SitePreferenceRepository {
    fun saveIdentifier(identifier: String)
    fun removeIdentifier(identifier: String)
    fun getIdentifier(): String?

    fun saveDeviceToken(token: String)
    fun getDeviceToken(): String?
    fun removeDeviceToken()

    fun clearAll()
}

internal class SitePreferenceRepositoryImpl(
    context: Context,
    private val siteId: String
) : PreferenceStore(context), SitePreferenceRepository {

    override val prefsName: String by lazy {
        "io.customer.sdk.${context.packageName}.$siteId"
    }

    companion object {
        private const val KEY_IDENTIFIER = "identifier"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }

    override fun saveIdentifier(identifier: String) = prefs.edit {
        putString(KEY_IDENTIFIER, identifier)
    }

    override fun removeIdentifier(identifier: String) = prefs.edit {
        remove(KEY_IDENTIFIER)
    }

    override fun getIdentifier(): String? = prefs.read {
        getString(KEY_IDENTIFIER, null)
    }

    override fun saveDeviceToken(token: String) = prefs.edit {
        putString(KEY_DEVICE_TOKEN, token)
    }

    override fun getDeviceToken(): String? = prefs.read {
        getString(KEY_DEVICE_TOKEN, null)
    }

    override fun removeDeviceToken() = prefs.edit {
        remove(KEY_DEVICE_TOKEN)
    }
}
