package io.customer.datapipelines.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

/**
 * Store for persisting location data across app restarts.
 *
 * Maintains two location references:
 * - **Cached**: the latest received location, used for identify enrichment.
 * - **Synced**: the last location actually sent to the server, used by
 *   the filter to decide whether a new location should be sent.
 */
internal interface LocationPreferenceStore {
    fun saveCachedLocation(latitude: Double, longitude: Double)
    fun getCachedLatitude(): Double?
    fun getCachedLongitude(): Double?

    fun saveSyncedLocation(latitude: Double, longitude: Double, timestamp: Long)
    fun getSyncedLatitude(): Double?
    fun getSyncedLongitude(): Double?
    fun getSyncedTimestamp(): Long?
    fun clearSyncedData()

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

    // -- Cached location (latest received, for identify enrichment) --

    override fun saveCachedLocation(latitude: Double, longitude: Double) = prefs.edit {
        putString(KEY_CACHED_LATITUDE, crypto.encrypt(latitude.toString()))
        putString(KEY_CACHED_LONGITUDE, crypto.encrypt(longitude.toString()))
    }

    override fun getCachedLatitude(): Double? = prefs.read {
        getString(KEY_CACHED_LATITUDE, null)?.let { crypto.decrypt(it).toDoubleOrNull() }
    }

    override fun getCachedLongitude(): Double? = prefs.read {
        getString(KEY_CACHED_LONGITUDE, null)?.let { crypto.decrypt(it).toDoubleOrNull() }
    }

    // -- Synced location (last sent to server, for filter comparison) --

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
        private const val KEY_ALIAS = "cio_location_key"
        private const val KEY_CACHED_LATITUDE = "cio_location_cached_latitude"
        private const val KEY_CACHED_LONGITUDE = "cio_location_cached_longitude"
        private const val KEY_SYNCED_LATITUDE = "cio_location_synced_latitude"
        private const val KEY_SYNCED_LONGITUDE = "cio_location_synced_longitude"
        private const val KEY_SYNCED_TIMESTAMP = "cio_location_synced_timestamp"
    }
}
