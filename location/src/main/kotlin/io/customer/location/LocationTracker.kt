package io.customer.location

import android.location.Location
import io.customer.location.store.LocationPreferenceStore
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.LocationCache
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
 *
 * Unlike the datapipelines version, this tracker does NOT gate on userId.
 * The userId gate is handled by datapipelines when it subscribes to
 * [Event.TrackLocationEvent].
 */
internal class LocationTracker(
    private val locationCache: LocationCache?,
    private val locationPreferenceStore: LocationPreferenceStore,
    private val logger: Logger,
    private val eventBus: EventBus
) {
    /**
     * Reads persisted cached location from the preference store and sets it on
     * the [LocationCache] so that identify events have location context
     * immediately after SDK restart.
     */
    fun restorePersistedLocation() {
        val lat = locationPreferenceStore.getCachedLatitude() ?: return
        val lng = locationPreferenceStore.getCachedLongitude() ?: return
        locationCache?.lastLocation = Event.LocationData(latitude = lat, longitude = lng)
        logger.debug("Restored persisted location: lat=$lat, lng=$lng")
    }

    /**
     * Processes an incoming location: always caches in the plugin and
     * persists coordinates for identify enrichment. Publishes a
     * [Event.TrackLocationEvent] when the [shouldSync] filter passes.
     */
    fun onLocationReceived(location: Event.LocationData) {
        logger.debug("Location update received: lat=${location.latitude}, lng=${location.longitude}")

        // Always cache and persist so identifies have context and location
        // survives app restarts — regardless of whether we send a track
        locationCache?.lastLocation = location
        locationPreferenceStore.saveCachedLocation(location.latitude, location.longitude)

        if (!shouldSync(location.latitude, location.longitude)) {
            logger.debug("Location cached but track suppressed: filter not met")
            return
        }

        logger.debug("Location filter passed, publishing TrackLocationEvent")
        eventBus.publish(Event.TrackLocationEvent(location = location))
    }

    /**
     * Re-evaluates whether the cached location should be synced. Called on
     * identify (via [Event.UserChangedEvent]) and on cold start to handle
     * cases where:
     * - An identify was sent without location context in a previous session,
     *   and location has since arrived.
     * - The app was restarted after >24h and the cached location should be
     *   re-sent.
     */
    fun syncCachedLocationIfNeeded() {
        val lat = locationPreferenceStore.getCachedLatitude() ?: return
        val lng = locationPreferenceStore.getCachedLongitude() ?: return

        if (!shouldSync(lat, lng)) return

        logger.debug("Syncing cached location: lat=$lat, lng=$lng")
        eventBus.publish(Event.TrackLocationEvent(location = Event.LocationData(latitude = lat, longitude = lng)))
    }

    /**
     * Records that a location was successfully queued for delivery.
     * Called when [Event.LocationTrackedEvent] is received, confirming
     * that datapipelines sent the track.
     */
    fun confirmSync(latitude: Double, longitude: Double) {
        locationPreferenceStore.saveSyncedLocation(latitude, longitude, System.currentTimeMillis())
    }

    /**
     * Resets user-level location state on logout or profile switch.
     * Clears synced data (timestamp and synced coordinates) so the next
     * user gets their own location track — not gated by the previous
     * user's 24h window. Cached coordinates are kept as they are
     * device-level, not user-level.
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
