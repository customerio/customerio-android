package io.customer.sdk.repository

import android.content.Context
import android.content.SharedPreferences
import io.customer.sdk.CustomerIO

internal interface PreferenceRepository {
    fun saveIdentifier(identifier: String)
    fun removeIdentifier(identifier: String)
    fun getIdentifier(): String?
}

internal class PreferenceRepositoryImpl(private val context: Context) : PreferenceRepository {

    companion object {
        private const val PREFS_NAME = "customer_io_store"
        private const val KEY_IDENTIFIER = "identifier"
    }

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(
            CustomerIO.instance().config.siteId,
            Context.MODE_PRIVATE
        )
    }

    override fun saveIdentifier(identifier: String) {
        prefs.edit().putString(KEY_IDENTIFIER, identifier).apply()
    }

    override fun removeIdentifier(identifier: String) {
        prefs.edit().remove(KEY_IDENTIFIER).apply()
    }

    override fun getIdentifier(): String? {
        return try {
            prefs.getString(KEY_IDENTIFIER, null)
        } catch (e: Exception) {
            null
        }
    }
}
