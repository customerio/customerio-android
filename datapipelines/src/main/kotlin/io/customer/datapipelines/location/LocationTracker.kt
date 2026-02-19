package io.customer.datapipelines.location

import android.location.Location
import io.customer.datapipelines.plugins.LocationPlugin
import io.customer.datapipelines.store.LocationPreferenceStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Logger

/**
 * Coordinates all location state management: persistence, restoration,
 * and filter-based sync decisions.
 *
 * Maintains two location references via [LocationPreferenceStore]:
 * - **Cached**: the latest received location, used for identify enrichment.
 * - **Synced**: the last location actually sent to the server, used by
 *   [shouldSync] to decide whether a new "Location Update" track should be sent.
 *
 * A "Location Update" track is sent only when both conditions are met:
 * 1. >= 24 hours since the last sync, AND
 * 2. >= 1 km distance from the last synced location.
 *
 * If no synced location exists yet (first time), the filter passes automatically.
 */
internal class LocationTracker(
    private val locationPlugin: LocationPlugin,
    private val locationPreferenceStore: LocationPreferenceStore,
    private val logger: Logger,
    private val userIdProvider: () -> String?
) {
    /**
     * Reads persisted cached location from the preference store and sets it on
     * the [LocationPlugin] so that identify events have location context
     * immediately after SDK restart.
     */
    fun restorePersistedLocation() {
        val lat = locationPreferenceStore.getCachedLatitude() ?: return
        val lng = locationPreferenceStore.getCachedLongitude() ?: return
        locationPlugin.lastLocation = Event.LocationData(latitude = lat, longitude = lng)
        logger.debug("Restored persisted location: lat=$lat, lng=$lng")
    }

    /**
     * Processes an incoming location event: always caches in the plugin and
     * persists coordinates for identify enrichment. Only returns non-null
     * (signalling the caller to send a "Location Update" track) when the
     * [shouldSync] filter passes.
     *
     * @return the [Event.LocationData] to send as a track, or null if suppressed.
     */
    fun onLocationReceived(event: Event.TrackLocationEvent): Event.LocationData? {
        val location = event.location
        logger.debug("Location update received: lat=${location.latitude}, lng=${location.longitude}")

        // Always cache and persist so identifies have context and location
        // survives app restarts — regardless of whether we send a track
        locationPlugin.lastLocation = location
        locationPreferenceStore.saveCachedLocation(location.latitude, location.longitude)

        // Only send location tracks for identified users
        if (userIdProvider().isNullOrEmpty()) {
            logger.debug("Location cached but track skipped: no identified user")
            return null
        }

        if (!shouldSync(location.latitude, location.longitude)) {
            logger.debug("Location cached but track suppressed: filter not met")
            return null
        }

        logger.debug("Location filter passed, sending Location Update track")
        return location
    }

    /**
     * Re-evaluates whether the cached location should be synced. Called on
     * identify and on cold start to handle cases where:
     * - An identify was sent without location context in a previous session,
     *   and location has since arrived.
     * - The app was restarted after >24h and the cached location should be
     *   re-sent.
     *
     * @return the [Event.LocationData] to send as a track, or null if
     *   the filter does not pass or no cached location exists.
     */
    fun syncCachedLocationIfNeeded(): Event.LocationData? {
        if (userIdProvider().isNullOrEmpty()) return null

        val lat = locationPreferenceStore.getCachedLatitude() ?: return null
        val lng = locationPreferenceStore.getCachedLongitude() ?: return null

        if (!shouldSync(lat, lng)) return null

        logger.debug("Syncing cached location: lat=$lat, lng=$lng")
        return Event.LocationData(latitude = lat, longitude = lng)
    }

    /**
     * Returns true if the [LocationPlugin] has a cached location.
     */
    fun hasLocationContext(): Boolean = locationPlugin.lastLocation != null

    /**
     * Resets user-level location state on logout. Clears synced data
     * (timestamp and synced coordinates) so the next user gets their own
     * location track — not gated by the previous user's 24h window.
     * Cached coordinates are kept as they are device-level, not user-level.
     */
    fun onUserReset() {
        locationPreferenceStore.clearSyncedData()
        logger.debug("User reset: cleared synced location data")
    }

    /**
     * Determines whether a location should be synced to the server based on
     * two criteria:
     * 1. **Time**: >= [LOCATION_RESEND_INTERVAL_MS] since the last sync.
     * 2. **Distance**: >= [MINIMUM_DISTANCE_METERS] from the last synced location.
     *
     * If no synced location exists yet, the filter passes automatically.
     */
    private fun shouldSync(latitude: Double, longitude: Double): Boolean {
        val lastTimestamp = locationPreferenceStore.getSyncedTimestamp()
        // Never synced before — always pass
        if (lastTimestamp == null) return true

        val timeSinceLastSync = System.currentTimeMillis() - lastTimestamp
        if (timeSinceLastSync < LOCATION_RESEND_INTERVAL_MS) return false

        val syncedLat = locationPreferenceStore.getSyncedLatitude() ?: return true
        val syncedLng = locationPreferenceStore.getSyncedLongitude() ?: return true

        val distance = distanceBetween(syncedLat, syncedLng, latitude, longitude)
        return distance >= MINIMUM_DISTANCE_METERS
    }

    /**
     * Records that a location was successfully queued for delivery.
     * Must be called AFTER the "Location Update" track has been enqueued
     * via [analytics.track], so that if the process is killed between
     * the track and the record, the worst case is a harmless duplicate
     * rather than a missed send.
     */
    fun confirmSync(latitude: Double, longitude: Double) {
        locationPreferenceStore.saveSyncedLocation(latitude, longitude, System.currentTimeMillis())
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
