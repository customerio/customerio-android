package io.customer.datapipelines.location

import io.customer.datapipelines.plugins.LocationPlugin
import io.customer.datapipelines.store.LocationPreferenceStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Logger

/**
 * Coordinates all location state management: persistence, restoration,
 * staleness detection, and tracking whether an identify was sent without
 * location context.
 */
internal class LocationTracker(
    private val locationPlugin: LocationPlugin,
    private val locationPreferenceStore: LocationPreferenceStore,
    private val logger: Logger
) {
    /**
     * Set when an identify is sent while no location context is available.
     * Cleared when a location update arrives, so the caller can react
     * (e.g. send a "Location Update" track for the newly-identified user).
     */
    @Volatile
    internal var identifySentWithoutLocation: Boolean = false

    /**
     * Reads persisted location from the preference store and sets it on the
     * [LocationPlugin] so that identify events have location context immediately
     * after SDK restart.
     */
    fun restorePersistedLocation() {
        val lat = locationPreferenceStore.getLatitude() ?: return
        val lng = locationPreferenceStore.getLongitude() ?: return
        locationPlugin.lastLocation = Event.LocationData(latitude = lat, longitude = lng)
        logger.debug("Restored persisted location: lat=$lat, lng=$lng")
    }

    /**
     * Processes an incoming location event: always caches in the plugin and
     * persists coordinates for identify enrichment. Only returns non-null
     * (signalling the caller to send a "Location Update" track) when:
     *
     * 1. An identify was previously sent without location context, OR
     * 2. >=24 hours have elapsed since the last "Location Update" track.
     *
     * @return the [Event.LocationData] to send as a track, or null if suppressed.
     */
    fun onLocationReceived(event: Event.TrackLocationEvent): Event.LocationData? {
        val location = event.location
        logger.debug("location update received: lat=${location.latitude}, lng=${location.longitude}")

        // Always cache and persist so identifies have context and location
        // survives app restarts — regardless of whether we send a track
        locationPlugin.lastLocation = location
        locationPreferenceStore.saveLocation(location.latitude, location.longitude)

        val shouldSendTrack = when {
            identifySentWithoutLocation -> {
                logger.debug("Sending location track: identify was previously sent without location context")
                identifySentWithoutLocation = false
                true
            }
            isStale() -> {
                logger.debug("Sending location track: >=24h since last send")
                true
            }
            else -> {
                logger.debug("Location cached but track suppressed: last sent <24h ago")
                false
            }
        }

        if (shouldSendTrack) {
            locationPreferenceStore.saveLastSentTimestamp(System.currentTimeMillis())
            return location
        }
        return null
    }

    /**
     * Returns the persisted location if more than 24 hours have elapsed since
     * the last "Location Update" track was sent, or null otherwise.
     * Updates the sent timestamp so the next cold start won't re-send.
     */
    fun getStaleLocationForResend(): Event.LocationData? {
        val lat = locationPreferenceStore.getLatitude() ?: return null
        val lng = locationPreferenceStore.getLongitude() ?: return null

        if (!isStale()) return null

        logger.debug("Location update stale on cold start, re-sending")
        locationPreferenceStore.saveLastSentTimestamp(System.currentTimeMillis())
        return Event.LocationData(latitude = lat, longitude = lng)
    }

    private fun isStale(): Boolean {
        val lastSent = locationPreferenceStore.getLastSentTimestamp() ?: return true
        return (System.currentTimeMillis() - lastSent) >= LOCATION_RESEND_INTERVAL_MS
    }

    /**
     * Records that an identify call was made without location context.
     */
    fun onIdentifySentWithoutLocation() {
        identifySentWithoutLocation = true
        logger.debug("Identify sent without location context; will send location track when location arrives")
    }

    /**
     * Clears the [identifySentWithoutLocation] flag. Called when the user
     * logs out — the debt belongs to the identified user, and once they're
     * gone the follow-up location track is no longer owed.
     */
    fun onUserReset() {
        if (identifySentWithoutLocation) {
            logger.debug("User reset; clearing pending identify-without-location flag")
            identifySentWithoutLocation = false
        }
    }

    /**
     * Returns true if the [LocationPlugin] has a cached location.
     */
    fun hasLocationContext(): Boolean = locationPlugin.lastLocation != null

    companion object {
        private const val LOCATION_RESEND_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }
}
