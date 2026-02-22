package io.customer.datapipelines.location

import android.location.Location
import io.customer.datapipelines.store.LocationSyncStore

/**
 * Determines whether a location update should be sent to the server and
 * atomically records the synced data when the filter passes.
 *
 * A location update is sent only when both conditions are met:
 * 1. >= 24 hours since the last sync, AND
 * 2. >= 1 km distance from the last synced location.
 *
 * If no synced location exists yet (first time or after reset), the filter
 * passes automatically.
 *
 * This filter lives in datapipelines (same module as the userId gate) so the
 * entire flow is synchronous — no round-trip confirmation events needed.
 */
internal class LocationSyncFilter(
    private val store: LocationSyncStore
) {
    /**
     * Checks whether the given location passes the sync filter (24h + 1km).
     * If the filter passes, atomically saves the location as the new synced
     * reference point and returns `true`. Otherwise returns `false`.
     */
    fun filterAndRecord(latitude: Double, longitude: Double): Boolean {
        val lastTimestamp = store.getSyncedTimestamp()
        // Never synced before — always pass
        if (lastTimestamp == null) {
            store.saveSyncedLocation(latitude, longitude, System.currentTimeMillis())
            return true
        }

        val timeSinceLastSync = System.currentTimeMillis() - lastTimestamp
        if (timeSinceLastSync < LOCATION_RESEND_INTERVAL_MS) return false

        val syncedLat = store.getSyncedLatitude() ?: run {
            store.saveSyncedLocation(latitude, longitude, System.currentTimeMillis())
            return true
        }
        val syncedLng = store.getSyncedLongitude() ?: run {
            store.saveSyncedLocation(latitude, longitude, System.currentTimeMillis())
            return true
        }

        val distance = distanceBetween(syncedLat, syncedLng, latitude, longitude)
        if (distance >= MINIMUM_DISTANCE_METERS) {
            store.saveSyncedLocation(latitude, longitude, System.currentTimeMillis())
            return true
        }

        return false
    }

    /**
     * Resets filter state on user switch or logout so the next user is not
     * suppressed by the previous user's 24h/1km window.
     */
    fun clearSyncedData() {
        store.clearSyncedData()
    }

    /**
     * Computes the distance in meters between two lat/lng points using
     * [android.location.Location.distanceBetween]. This is a static math
     * utility — no location permissions required.
     */
    private fun distanceBetween(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }

    companion object {
        private const val LOCATION_RESEND_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val MINIMUM_DISTANCE_METERS = 1000f // 1 km
    }
}
