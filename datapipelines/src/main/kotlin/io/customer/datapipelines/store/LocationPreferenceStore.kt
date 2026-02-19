package io.customer.datapipelines.store

import android.content.Context
import androidx.core.content.edit
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
    context: Context
) : PreferenceStore(context), LocationPreferenceStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.location.${context.packageName}"
    }

    override fun saveLocation(latitude: Double, longitude: Double) = prefs.edit {
        // Store as String to preserve Double precision (putFloat truncates)
        putString(KEY_LATITUDE, latitude.toString())
        putString(KEY_LONGITUDE, longitude.toString())
    }

    override fun saveLastSentTimestamp(timestamp: Long) = prefs.edit {
        putLong(KEY_LAST_SENT_TIMESTAMP, timestamp)
    }

    override fun getLatitude(): Double? = prefs.read {
        getString(KEY_LATITUDE, null)?.toDoubleOrNull()
    }

    override fun getLongitude(): Double? = prefs.read {
        getString(KEY_LONGITUDE, null)?.toDoubleOrNull()
    }

    override fun getLastSentTimestamp(): Long? = prefs.read {
        if (contains(KEY_LAST_SENT_TIMESTAMP)) getLong(KEY_LAST_SENT_TIMESTAMP, 0L) else null
    }

    companion object {
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_LAST_SENT_TIMESTAMP = "last_sent_timestamp"
    }
}
