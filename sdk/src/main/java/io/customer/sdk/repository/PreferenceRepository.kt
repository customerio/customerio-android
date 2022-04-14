package io.customer.sdk.repository

import android.content.Context
import android.content.SharedPreferences
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.extenstions.unixTimeToDate
import io.customer.sdk.CustomerIOConfig
import java.util.*

interface PreferenceRepository {
    fun saveIdentifier(identifier: String)
    fun removeIdentifier(identifier: String)
    fun getIdentifier(): String?

    fun saveDeviceToken(token: String)
    fun getDeviceToken(): String?

    var httpRequestsPauseEnds: Date?
}

internal class PreferenceRepositoryImpl(
    private val context: Context,
    private val config: CustomerIOConfig
) : PreferenceRepository {

    private val prefsName: String by lazy {
        "io.customer.sdk.${context.packageName}.${config.siteId}"
    }

    companion object {
        private const val KEY_IDENTIFIER = "identifier"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_HTTP_PAUSE_ENDS = "http_pause_ends"
    }

    private val prefs: SharedPreferences
        get() = context.applicationContext.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )

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
        get() {
            prefs.getLong(KEY_HTTP_PAUSE_ENDS, Long.MIN_VALUE).let {
                return if (it == Long.MIN_VALUE) null else it.unixTimeToDate()
            }
        }
        set(value) {
            val newValue = value?.getUnixTimestamp() ?: Long.MIN_VALUE

            prefs.edit().putLong(KEY_HTTP_PAUSE_ENDS, newValue).apply()
        }
}
