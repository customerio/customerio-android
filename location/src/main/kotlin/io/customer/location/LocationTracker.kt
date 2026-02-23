package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.LocationCache
import io.customer.sdk.core.util.Logger

/**
 * Coordinates all location state management: persistence, restoration,
 * and publishing location updates to datapipelines.
 *
 * Maintains a cached location reference via [LocationPreferenceStore]:
 * - **Cached**: the latest received location, used for identify enrichment
 *   and surviving app restarts.
 *
 * Every location update is published as a [Event.TrackLocationEvent].
 * Filtering (24h + 1km) and the userId gate are handled synchronously
 * by datapipelines when it receives the event.
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
     * Processes an incoming location: caches in the plugin, persists
     * coordinates for identify enrichment, and publishes a
     * [Event.TrackLocationEvent] for datapipelines to filter and send.
     */
    fun onLocationReceived(location: Event.LocationData) {
        logger.debug("Location update received: lat=${location.latitude}, lng=${location.longitude}")

        locationCache?.lastLocation = location
        locationPreferenceStore.saveCachedLocation(location.latitude, location.longitude)

        logger.debug("Publishing TrackLocationEvent")
        eventBus.publish(Event.TrackLocationEvent(location = location))
    }

    /**
     * Re-publishes the cached location as a [Event.TrackLocationEvent].
     * Called on identify (via [Event.UserChangedEvent]) and on cold start
     * to handle cases where:
     * - An identify was sent without location context in a previous session,
     *   and location has since arrived.
     * - The app was restarted after >24h and the cached location should be
     *   re-evaluated by datapipelines.
     *
     * Datapipelines applies the sync filter, so this is safe to call
     * unconditionally when a user is identified.
     */
    fun syncCachedLocationIfNeeded() {
        val lat = locationPreferenceStore.getCachedLatitude() ?: return
        val lng = locationPreferenceStore.getCachedLongitude() ?: return

        logger.debug("Re-publishing cached location: lat=$lat, lng=$lng")
        eventBus.publish(Event.TrackLocationEvent(location = Event.LocationData(latitude = lat, longitude = lng)))
    }

    /**
     * Clears all persisted location data from the preference store.
     * Called on [Event.ResetEvent] (clearIdentify) to ensure no stale
     * location survives a full identity reset.
     */
    fun clearCachedLocation() {
        locationPreferenceStore.clearCachedLocation()
        logger.debug("Cleared cached location from preference store")
    }
}
