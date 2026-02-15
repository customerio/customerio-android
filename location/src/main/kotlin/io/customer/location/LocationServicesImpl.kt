package io.customer.location

import android.location.Location
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.util.Logger

/**
 * Real implementation of [LocationServices].
 * Handles manual location setting with validation and config checks.
 *
 * SDK-managed location (requestLocationUpdateOnce) will be implemented in a future PR.
 */
internal class LocationServicesImpl(
    private val config: LocationModuleConfig,
    private val logger: Logger,
    private val eventBus: EventBus
) : LocationServices {

    override fun setLastKnownLocation(latitude: Double, longitude: Double) {
        if (!config.enableLocationTracking) {
            logger.debug("Location tracking is disabled, ignoring setLastKnownLocation.")
            return
        }

        if (!isValidCoordinate(latitude, longitude)) {
            logger.error("Invalid coordinates: lat=$latitude, lng=$longitude. Latitude must be [-90, 90] and longitude [-180, 180].")
            return
        }

        logger.debug("Tracking location: lat=$latitude, lng=$longitude")

        val locationData = Event.LocationData(
            latitude = latitude,
            longitude = longitude
        )
        eventBus.publish(Event.TrackLocationEvent(location = locationData))
    }

    override fun setLastKnownLocation(location: Location) {
        setLastKnownLocation(location.latitude, location.longitude)
    }

    override fun requestLocationUpdateOnce() {
        // Will be implemented in the SDK-managed location PR
        logger.debug("requestLocationUpdateOnce is not yet implemented.")
    }

    override fun stopLocationUpdates() {
        // Will be implemented in the SDK-managed location PR
        logger.debug("stopLocationUpdates is not yet implemented.")
    }

    companion object {
        /**
         * Validates that latitude is within [-90, 90] and longitude is within [-180, 180].
         * Also rejects NaN and Infinity values.
         */
        internal fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
            if (latitude.isNaN() || latitude.isInfinite()) return false
            if (longitude.isNaN() || longitude.isInfinite()) return false
            return latitude in -90.0..90.0 && longitude in -180.0..180.0
        }
    }
}
