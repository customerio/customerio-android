package io.customer.location

import android.location.Location
import io.customer.base.internal.InternalCustomerIOApi
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

    @Volatile
    private var currentLocationJob: Job? = null

    @Volatile
    private var currentRequestIntent: LocationRequestIntent? = null

    override fun setLastKnownLocation(latitude: Double, longitude: Double) {
        if (!config.isEnabled) {
            logger.debug("Location tracking is disabled, ignoring setLastKnownLocation.")
            return
        }

        if (!LocationCoordinates.isValid(latitude, longitude)) {
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
        launchLocationRequest(tracked = true)
    }

    @OptIn(InternalCustomerIOApi::class)
    override fun requestLocationUpdateSilently() {
        launchLocationRequest(tracked = false)
    }

    private fun launchLocationRequest(tracked: Boolean) {
        if (currentLocationJob?.isActive == true) {
            if (!tracked) return
            // Tracked intent must survive the gate (ON_APP_START and the geofence bootstrap
            // race for this slot on first identify) — upgrade the in-flight request instead.
            currentRequestIntent?.upgradeToTracked()
            // If the request finished before the upgrade applied, fall through to a fresh
            // one — a duplicate fix is deduped by the sync filter; a lost one isn't recoverable.
            if (currentLocationJob?.isActive == true) return
        }

        val intent = LocationRequestIntent(tracked)
        currentRequestIntent = intent
        currentLocationJob = scope.launch {
            try {
                orchestrator.requestLocation(intent)
            } finally {
                // Only clear if this is still the current job — prevents
                // a cancelled job's finally from nulling a newer job's reference
                if (currentLocationJob === coroutineContext[Job]) {
                    currentLocationJob = null
                }
            }
        }
    }

    @OptIn(InternalCustomerIOApi::class)
    override fun getLastKnownLocation(): LocationCoordinates? = locationTracker.lastKnownLocation

    /**
     * Cancels any in-flight location request.
     * Called when the app enters background to avoid unnecessary GPS work.
     *
     * @return true if an active request was cancelled, false if nothing was in flight.
     */
    internal fun cancelInFlightRequest(): Boolean {
        val job = currentLocationJob ?: return false
        currentLocationJob = null
        job.cancel()
        return true
    }
}
