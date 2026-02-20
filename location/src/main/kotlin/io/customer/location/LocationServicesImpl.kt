package io.customer.location

import android.location.Location
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Logger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

    private val lock = ReentrantLock()
    private var currentLocationJob: Job? = null

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
        locationTracker.onLocationReceived(locationData)
    }

    override fun setLastKnownLocation(location: Location) {
        setLastKnownLocation(location.latitude, location.longitude)
    }

    override fun requestLocationUpdate() {
        lock.withLock {
            // Cancel any previous in-flight request
            currentLocationJob?.cancel()

            currentLocationJob = scope.launch {
                val thisJob = coroutineContext[Job]
                try {
                    orchestrator.requestLocationUpdate()
                } finally {
                    lock.withLock {
                        if (currentLocationJob === thisJob) {
                            currentLocationJob = null
                        }
                    }
                }
            }
        }
    }

    override fun stopLocationUpdates() {
        val job: Job?
        lock.withLock {
            job = currentLocationJob
            currentLocationJob = null
        }
        // Cancelling the job triggers invokeOnCancellation in FusedLocationProvider's
        // suspendCancellableCoroutine, which cancels the CancellationTokenSource.
        job?.cancel()
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
