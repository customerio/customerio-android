package io.customer.location.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

/**
 * Store for persisting the cached (latest received) location across app restarts.
 *
 * Used for identify enrichment so that location context is available
 * immediately after SDK restart. Coordinates are encrypted at rest
 * since cached location is long-term device-level PII.
 */
internal interface LocationPreferenceStore {
    fun saveCachedLocation(latitude: Double, longitude: Double)
    fun getCachedLatitude(): Double?
    fun getCachedLongitude(): Double?
}

internal class LocationPreferenceStoreImpl(
    context: Context,
    logger: Logger
) : PreferenceStore(context), LocationPreferenceStore {

    private val crypto = PreferenceCrypto(KEY_ALIAS, logger)

    override val prefsName: String by lazy {
        "io.customer.sdk.location.${context.packageName}"
    }

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

    companion object {
        private const val KEY_ALIAS = "cio_location_key"
        private const val KEY_CACHED_LATITUDE = "cio_location_cached_latitude"
        private const val KEY_CACHED_LONGITUDE = "cio_location_cached_longitude"
    }
}
