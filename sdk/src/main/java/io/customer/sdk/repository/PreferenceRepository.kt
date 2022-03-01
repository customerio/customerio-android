package io.customer.sdk.repository

import android.content.Context
import android.content.SharedPreferences

interface PreferenceRepository {
    fun saveIdentifier(identifier: String)
    fun removeIdentifier(identifier: String)
    fun getIdentifier(): String?

    fun saveDeviceToken(token: String)
    fun removeDeviceToken(token: String)
    fun getDeviceToken(): String?
}

class PreferenceRepositoryImpl(
    private val context: Context,
    private val siteId: String
) : PreferenceRepository {

    private val prefsName by lazy {
        "io.customer.sdk.${context.packageName}.$siteId"
    }

    companion object {
        private const val KEY_IDENTIFIER = "identifier"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(
            prefsName,
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

    override fun saveDeviceToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    override fun removeDeviceToken(token: String) {
        prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    override fun getDeviceToken(): String? {
        return try {
            prefs.getString(KEY_DEVICE_TOKEN, null)
        } catch (e: Exception) {
            null
        }
    }
}
