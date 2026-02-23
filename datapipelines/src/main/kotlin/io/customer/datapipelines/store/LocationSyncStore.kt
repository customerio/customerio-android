package io.customer.datapipelines.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.PreferenceCrypto
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

/**
 * Store for persisting the last synced location data.
 *
 * Tracks the coordinates and timestamp of the last location successfully
 * sent to the server, used by [io.customer.datapipelines.location.LocationSyncFilter]
 * to decide whether a new location update should be sent.
 *
 * Coordinates are encrypted at rest using [PreferenceCrypto] (AES-256-GCM
 * via Android Keystore) to protect location PII on the device.
 */
internal interface LocationSyncStore {
    fun saveSyncedLocation(latitude: Double, longitude: Double, timestamp: Long)
    fun getSyncedLatitude(): Double?
    fun getSyncedLongitude(): Double?
    fun getSyncedTimestamp(): Long?
    fun clearSyncedData()
}

internal class LocationSyncStoreImpl(
    context: Context,
    logger: Logger
) : PreferenceStore(context), LocationSyncStore {

    private val crypto = PreferenceCrypto(KEY_ALIAS, logger)

    override val prefsName: String by lazy {
        "io.customer.sdk.location_sync.${context.packageName}"
    }

    override fun saveSyncedLocation(latitude: Double, longitude: Double, timestamp: Long) = prefs.edit {
        putString(KEY_SYNCED_LATITUDE, crypto.encrypt(latitude.toString()))
        putString(KEY_SYNCED_LONGITUDE, crypto.encrypt(longitude.toString()))
        putLong(KEY_SYNCED_TIMESTAMP, timestamp)
    }

    override fun getSyncedLatitude(): Double? = prefs.read {
        getString(KEY_SYNCED_LATITUDE, null)?.let { crypto.decrypt(it).toDoubleOrNull() }
    }

    override fun getSyncedLongitude(): Double? = prefs.read {
        getString(KEY_SYNCED_LONGITUDE, null)?.let { crypto.decrypt(it).toDoubleOrNull() }
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
        private const val KEY_ALIAS = "cio_location_sync_key"
        private const val KEY_SYNCED_LATITUDE = "cio_synced_latitude"
        private const val KEY_SYNCED_LONGITUDE = "cio_synced_longitude"
        private const val KEY_SYNCED_TIMESTAMP = "cio_synced_timestamp"
    }
}
