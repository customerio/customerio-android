package io.customer.datapipelines.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

/**
 * Store for persisting location data across app restarts.
 * Ensures identify events always have location context and supports
 * 24-hour re-send of stale location updates on SDK startup.
 */
internal interface LocationPreferenceStore {
    fun saveLocation(latitude: Double, longitude: Double)
    fun saveLastSentTimestamp(timestamp: Long)
    fun getLatitude(): Double?
    fun getLongitude(): Double?
    fun getLastSentTimestamp(): Long?
    fun clearAll()
}

internal class LocationPreferenceStoreImpl(
    context: Context,
    logger: Logger
) : PreferenceStore(context), LocationPreferenceStore {

    private val crypto = PreferenceCrypto(KEY_ALIAS, logger)

    override val prefsName: String by lazy {
        "io.customer.sdk.location.${context.packageName}"
    }

    override fun saveLocation(latitude: Double, longitude: Double) = prefs.edit {
        putString(KEY_LATITUDE, crypto.encrypt(latitude.toString()))
        putString(KEY_LONGITUDE, crypto.encrypt(longitude.toString()))
    }

    override fun saveLastSentTimestamp(timestamp: Long) = prefs.edit {
        putLong(KEY_LAST_SENT_TIMESTAMP, timestamp)
    }

    override fun getLatitude(): Double? = prefs.read {
        getString(KEY_LATITUDE, null)?.let { crypto.decrypt(it).toDoubleOrNull() }
    }

    override fun getLongitude(): Double? = prefs.read {
        getString(KEY_LONGITUDE, null)?.let { crypto.decrypt(it).toDoubleOrNull() }
    }

    override fun getLastSentTimestamp(): Long? = prefs.read {
        if (contains(KEY_LAST_SENT_TIMESTAMP)) getLong(KEY_LAST_SENT_TIMESTAMP, 0L) else null
    }

    companion object {
        private const val KEY_ALIAS = "cio_location_key"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_LAST_SENT_TIMESTAMP = "last_sent_timestamp"
    }
}
