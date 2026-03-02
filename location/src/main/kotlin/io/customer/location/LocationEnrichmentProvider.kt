package io.customer.location

import io.customer.location.store.LocationPreferenceStore
import io.customer.sdk.core.pipeline.IdentifyHook
import io.customer.sdk.core.util.Logger

/**
 * Provides location data for identify event context enrichment.
 *
 * Every identify() call enriches the event context with the latest
 * location coordinates. This is unfiltered — a new user always gets
 * the device's current location on their profile immediately.
 *
 * On clearIdentify(), [resetContext] clears the in-memory cache and
 * persisted coordinates synchronously during analytics.reset().
 */
internal class LocationEnrichmentProvider(
    private val locationPreferenceStore: LocationPreferenceStore,
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

    override fun resetContext() {
        lastLocation = null
        locationPreferenceStore.clearCachedLocation()
        logger.debug("Location enrichment state reset")
    }

    /**
     * Updates the in-memory location cache used for identify context.
     * Called by [LocationSyncCoordinator] when a new location is received.
     */
    fun updateLocation(coordinates: LocationCoordinates) {
        lastLocation = coordinates
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
}
