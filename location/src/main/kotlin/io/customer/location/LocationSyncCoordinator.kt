package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.location.sync.LocationSyncFilter
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.pipeline.IdentifyHook
import io.customer.sdk.core.util.Logger
import io.customer.sdk.util.EventNames

/**
 * Coordinates location persistence and "Location Update" track events.
 *
 * When a location is received, it is persisted, the enrichment provider's
 * in-memory cache is updated, and the sync filter is evaluated to decide
 * whether a track event should be sent. The track event is gated by a
 * userId check and the 24h / 1km sync filter to avoid redundant events.
 *
 * Implements [IdentifyHook] solely for [resetContext] — clears sync filter
 * state on clearIdentify() so the next user starts with a fresh baseline.
 */
internal class LocationSyncCoordinator(
    private val dataPipeline: DataPipeline?,
    private val locationPreferenceStore: LocationPreferenceStore,
    private val locationSyncFilter: LocationSyncFilter,
    private val enrichmentProvider: LocationEnrichmentProvider,
    private val logger: Logger
) : IdentifyHook {

    override fun getIdentifyContext(): Map<String, Any> = emptyMap()

    override fun resetContext() {
        locationSyncFilter.clearSyncedData()
        logger.debug("Location sync state reset")
    }

    /**
     * Processes an incoming location: updates the enrichment provider's
     * in-memory cache, persists coordinates, and attempts to send a
     * location track event.
     */
    fun onLocationReceived(latitude: Double, longitude: Double) {
        logger.debug("Location update received: lat=$latitude, lng=$longitude")

        enrichmentProvider.updateLocation(LocationCoordinates(latitude = latitude, longitude = longitude))
        locationPreferenceStore.saveCachedLocation(latitude, longitude)

        trySendLocationTrack(latitude, longitude)
    }

    /**
     * Called when a user is identified. Attempts to sync the cached
     * location as a track event for the newly identified user.
     *
     * The identify event itself already carries location via
     * [LocationEnrichmentProvider.getIdentifyContext] — this method handles
     * the supplementary "Location Update" track event, subject to the sync filter.
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
