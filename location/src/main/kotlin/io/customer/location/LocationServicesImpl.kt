package io.customer.location

import android.location.Location
import io.customer.sdk.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Real implementation of [LocationServices].
 * Handles manual location setting with validation and config checks,
 * and SDK-managed one-shot location via [LocationOrchestrator].
 */
internal class LocationServicesImpl(
    private val config: LocationModuleConfig,
    private val logger: Logger,
    private val locationTracker: LocationTracker,
    private val orchestrator: LocationOrchestrator,
    private val scope: CoroutineScope
) : LocationServices {

    private var currentLocationJob: Job? = null

    override fun setLastKnownLocation(latitude: Double, longitude: Double) {
        if (!config.isEnabled) {
            logger.debug("Location tracking is disabled, ignoring setLastKnownLocation.")
            return
        }

        if (!isValidCoordinate(latitude, longitude)) {
            logger.error("Invalid coordinates: lat=$latitude, lng=$longitude. Latitude must be [-90, 90] and longitude [-180, 180].")
            return
        }

        logger.debug("Tracking location: lat=$latitude, lng=$longitude")

        locationTracker.onLocationReceived(latitude, longitude)
    }

    override fun setLastKnownLocation(location: Location) {
        setLastKnownLocation(location.latitude, location.longitude)
    }

    override fun requestLocationUpdate() {
        // If a request is already in flight, ignore the new call
        if (currentLocationJob?.isActive == true) return

        currentLocationJob = scope.launch {
            try {
                orchestrator.requestLocationUpdate()
            } finally {
                currentLocationJob = null
            }
        }
    }

    /**
     * Cancels any in-flight location request.
     * Called when the app enters background to avoid unnecessary GPS work.
     */
    internal fun cancelInFlightRequest() {
        currentLocationJob?.cancel()
        currentLocationJob = null
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
