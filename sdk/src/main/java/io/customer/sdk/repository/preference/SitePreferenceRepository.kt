package io.customer.sdk.repository.preference

import android.content.Context
import android.content.SharedPreferences
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.extensions.getDate
import io.customer.sdk.extensions.putDate
import java.util.*

interface SitePreferenceRepository {
    fun saveIdentifier(identifier: String)
    fun removeIdentifier(identifier: String)
    fun getIdentifier(): String?

    fun saveDeviceToken(token: String)
    fun getDeviceToken(): String?

    fun clearAll()

    var httpRequestsPauseEnds: Date?
}

internal class SitePreferenceRepositoryImpl(
    context: Context,
    private val config: CustomerIOConfig
) : BasePreferenceRepository(context), SitePreferenceRepository {

    override val prefsName: String by lazy {
        "io.customer.sdk.${context.packageName}.${config.siteId}"
    }

    override val prefs: SharedPreferences
        get() = context.applicationContext.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )

    companion object {
        private const val KEY_IDENTIFIER = "identifier"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_HTTP_PAUSE_ENDS = "http_pause_ends"
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

    override fun getDeviceToken(): String? {
        return try {
            prefs.getString(KEY_DEVICE_TOKEN, null)
        } catch (e: Exception) {
            null
        }
    }

    override var httpRequestsPauseEnds: Date?
        get() = prefs.getDate(KEY_HTTP_PAUSE_ENDS)
        set(value) {
            prefs.edit().apply {
                putDate(KEY_HTTP_PAUSE_ENDS, value)
                apply()
            }
        }
}
