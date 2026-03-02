package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.location.sync.LocationSyncFilter
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.pipeline.IdentifyHook
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames

/**
 * Coordinates all location state management: persistence, restoration,
 * identify context enrichment, and sending location track events.
 *
 * Location reaches the backend through two independent paths:
 *
 * 1. **Identify context enrichment** — implements [IdentifyHook].
 *    Every identify() call enriches the event context with the latest
 *    location coordinates. This is unfiltered — a new user always gets
 *    the device's current location on their profile immediately.
 *
 * 2. **"Location Update" track event** — sent via [DataPipeline.track].
 *    Gated by a userId check and a sync filter (24h / 1km threshold)
 *    to avoid redundant events. This creates a discrete event in the
 *    user's activity timeline for journey/segment triggers.
 *
 * Profile switch handling is intentionally not tracked here.
 * On clearIdentify(), [resetContext] clears all state (cache, persistence,
 * sync filter) synchronously during analytics.reset(). On identify(), the
 * new user's profile receives the location via path 1 regardless of the
 * sync filter's state.
 */
internal class LocationTracker(
    private val dataPipeline: DataPipeline?,
    private val locationPreferenceStore: LocationPreferenceStore,
    private val locationSyncFilter: LocationSyncFilter,
    private val logger: Logger
) : IdentifyHook {

    @Volatile
    private var lastLocation: LocationCoordinates? = null

    override fun getIdentifyContext(): Map<String, Any> {
        val location = lastLocation ?: return emptyMap()
        return mapOf(
            "location_latitude" to location.latitude,
            "location_longitude" to location.longitude
        )
    }

    /**
     * Called synchronously by analytics.reset() during clearIdentify.
     * Clears all location state: in-memory cache, persisted coordinates,
     * and sync filter — similar to how device tokens and other per-user
     * state are cleared on reset. This runs before ResetEvent is published,
     * guaranteeing no stale data is available for a subsequent identify().
     */
    override fun resetContext() {
        lastLocation = null
        locationPreferenceStore.clearCachedLocation()
        locationSyncFilter.clearSyncedData()
        logger.debug("Location state reset")
    }

    /**
     * Reads persisted cached location from the preference store and sets the
     * in-memory cache so that identify events have location context
     * immediately after SDK restart.
     */
    fun restorePersistedLocation() {
        val lat = locationPreferenceStore.getCachedLatitude() ?: return
        val lng = locationPreferenceStore.getCachedLongitude() ?: return
        lastLocation = LocationCoordinates(latitude = lat, longitude = lng)
        logger.debug("Restored persisted location: lat=$lat, lng=$lng")
    }

    /**
     * Processes an incoming location: caches in memory, persists
     * coordinates, and attempts to send a location track event.
     */
    fun onLocationReceived(latitude: Double, longitude: Double) {
        logger.debug("Location update received: lat=$latitude, lng=$longitude")

        lastLocation = LocationCoordinates(latitude = latitude, longitude = longitude)
        locationPreferenceStore.saveCachedLocation(latitude, longitude)

        trySendLocationTrack(latitude, longitude)
    }

    /**
     * Called when a user is identified. Attempts to sync the cached
     * location as a track event for the newly identified user.
     *
     * The identify event itself already carries location via
     * [getIdentifyContext] — this method handles the supplementary
     * "Location Update" track event, subject to the sync filter.
     */
    fun onUserIdentified() {
        syncCachedLocationIfNeeded()
    }

    /**
     * Re-evaluates the cached location for sending.
     * Called on identify (via [onUserIdentified]) and on cold start
     * (via replayed UserChangedEvent) to handle cases where a location
     * was cached but not yet sent for the current user.
     */
    internal fun syncCachedLocationIfNeeded() {
        val lat = locationPreferenceStore.getCachedLatitude() ?: return
        val lng = locationPreferenceStore.getCachedLongitude() ?: return

        logger.debug("Re-evaluating cached location: lat=$lat, lng=$lng")
        trySendLocationTrack(lat, lng)
    }

    /**
     * Applies the userId gate and sync filter, then sends a location
     * track event via [DataPipeline] if both pass.
     */
    private fun trySendLocationTrack(latitude: Double, longitude: Double) {
        val pipeline = dataPipeline ?: return
        if (!pipeline.isUserIdentified) return
        if (!locationSyncFilter.filterAndRecord(latitude, longitude)) return

        logger.debug("Sending location track: lat=$latitude, lng=$longitude")
        pipeline.track(
            name = EventNames.LOCATION_UPDATE,
            properties = mapOf(
                "latitude" to latitude,
                "longitude" to longitude
            )
        )
    }
}

/**
 * Internal location coordinate holder, replacing the cross-module Event.LocationData.
 */
internal data class LocationCoordinates(
    val latitude: Double,
    val longitude: Double
)
