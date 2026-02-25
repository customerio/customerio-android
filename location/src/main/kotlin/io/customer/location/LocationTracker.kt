package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.location.sync.LocationSyncFilter
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.pipeline.IdentifyContextProvider
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames

/**
 * Coordinates all location state management: persistence, restoration,
 * profile enrichment, and sending location track events.
 *
 * Implements [IdentifyContextProvider] to enrich identify event context
 * with the latest location. Sends location track events directly via
 * [DataPipeline], applying the userId gate and sync filter locally.
 *
 * Tracks the last known userId to detect profile switches and reset
 * the sync filter accordingly.
 */
internal class LocationTracker(
    private val dataPipeline: DataPipeline?,
    private val locationPreferenceStore: LocationPreferenceStore,
    private val locationSyncFilter: LocationSyncFilter,
    private val logger: Logger
) : IdentifyContextProvider {

    @Volatile
    private var lastLocation: LocationCoordinates? = null

    @Volatile
    private var lastKnownUserId: String? = null

    override fun getIdentifyContext(): Map<String, Any> {
        val location = lastLocation ?: return emptyMap()
        return mapOf(
            "location_latitude" to location.latitude,
            "location_longitude" to location.longitude
        )
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
     * Called when the user identity changes (identify or clearIdentify).
     * Detects profile switches to reset the sync filter, then attempts
     * to sync the cached location for the new user.
     */
    fun onUserChanged(userId: String?, anonymousId: String) {
        val previousUserId = lastKnownUserId
        lastKnownUserId = userId

        // Detect profile switch: previous user existed and differs from new user
        if (previousUserId != null && previousUserId != userId) {
            logger.debug("Profile switch detected ($previousUserId -> $userId), clearing sync filter")
            locationSyncFilter.clearSyncedData()
        }

        if (!userId.isNullOrEmpty()) {
            syncCachedLocationIfNeeded()
        }
    }

    /**
     * Clears all location state on identity reset (clearIdentify).
     * Resets in-memory cache, persisted location, and sync filter.
     */
    fun onReset() {
        lastLocation = null
        lastKnownUserId = null
        locationPreferenceStore.clearCachedLocation()
        locationSyncFilter.clearSyncedData()
        logger.debug("Location state reset")
    }

    /**
     * Re-evaluates the cached location for sending.
     * Called on identify (via [onUserChanged]) and on cold start to handle
     * cases where a location was cached but not yet sent for the current user.
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
