package io.customer.datapipelines.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

/**
 * Store for persisting the last synced location data.
 *
 * Tracks the coordinates and timestamp of the last location successfully
 * sent to the server, used by [io.customer.datapipelines.location.LocationSyncFilter]
 * to decide whether a new location update should be sent.
 *
 * Coordinates are stored as raw [Long] bits via [Double.toBits] for lossless
 * storage without encryption — this is the same data already sent to the server
 * via analytics.track().
 */
internal interface LocationSyncStore {
    fun saveSyncedLocation(latitude: Double, longitude: Double, timestamp: Long)
    fun getSyncedLatitude(): Double?
    fun getSyncedLongitude(): Double?
    fun getSyncedTimestamp(): Long?
    fun clearSyncedData()
}

internal class LocationSyncStoreImpl(
    context: Context
) : PreferenceStore(context), LocationSyncStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.location_sync.${context.packageName}"
    }

    override fun saveSyncedLocation(latitude: Double, longitude: Double, timestamp: Long) = prefs.edit {
        putLong(KEY_SYNCED_LATITUDE, latitude.toBits())
        putLong(KEY_SYNCED_LONGITUDE, longitude.toBits())
        putLong(KEY_SYNCED_TIMESTAMP, timestamp)
    }

    override fun getSyncedLatitude(): Double? = prefs.read {
        if (contains(KEY_SYNCED_LATITUDE)) Double.fromBits(getLong(KEY_SYNCED_LATITUDE, 0L)) else null
    }

    override fun getSyncedLongitude(): Double? = prefs.read {
        if (contains(KEY_SYNCED_LONGITUDE)) Double.fromBits(getLong(KEY_SYNCED_LONGITUDE, 0L)) else null
    }

    override fun getSyncedTimestamp(): Long? = prefs.read {
        if (contains(KEY_SYNCED_TIMESTAMP)) getLong(KEY_SYNCED_TIMESTAMP, 0L) else null
    }

    override fun clearSyncedData() = prefs.edit {
        remove(KEY_SYNCED_LATITUDE)
        remove(KEY_SYNCED_LONGITUDE)
        remove(KEY_SYNCED_TIMESTAMP)
    }

    companion object {
        private const val KEY_SYNCED_LATITUDE = "cio_synced_latitude"
        private const val KEY_SYNCED_LONGITUDE = "cio_synced_longitude"
        private const val KEY_SYNCED_TIMESTAMP = "cio_synced_timestamp"
    }
}
