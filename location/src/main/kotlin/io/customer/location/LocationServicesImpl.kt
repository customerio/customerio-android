package io.customer.location

import android.location.Location
import android.os.SystemClock
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

    override fun setLastKnownLocation(latitude: Double, longitude: Double) =
        trackHostLocation(latitude, longitude)

    override fun setLastKnownLocation(location: Location) =
        // Unlike the coordinate overload, this one carries what the host's fix could resolve.
        trackHostLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            fixElapsedRealtimeMillis = location.fixElapsedRealtimeMillis()
        )

    private fun trackHostLocation(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double? = null,
        fixElapsedRealtimeMillis: Long? = null
    ) {
        if (!config.isEnabled) {
            logger.debug("Location tracking is disabled, ignoring setLastKnownLocation.")
            return
        }

        if (!LocationCoordinates.isValid(latitude, longitude)) {
            logger.error("Invalid coordinates: lat=$latitude, lng=$longitude. Latitude must be [-90, 90] and longitude [-180, 180].")
            return
        }

        logger.debug("Tracking location: lat=$latitude, lng=$longitude")

        locationTracker.onLocationReceived(
            latitude,
            longitude,
            horizontalAccuracyMeters,
            fixElapsedRealtimeMillis
        )
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
            // race for this slot on first identify) — upgrade the in-flight request instead. If it
            // can no longer deliver, fall through to a fresh fetch: a duplicate fix is dropped by
            // the sync filter, a lost one isn't recoverable.
            if (currentRequestIntent?.upgradeToTracked() == true) return
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

/**
 * A host-built [Location] often stamps only wall-clock [Location.time]. Fall back to it, mapped onto
 * the monotonic base, so an old host fix is still aged rather than trusted as current.
 */
private fun Location.fixElapsedRealtimeMillis(): Long? =
    elapsedRealtimeNanos.takeIf { it > 0L }?.let { it / NANOS_PER_MILLI }
        ?: time.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - (System.currentTimeMillis() - it) }

private const val NANOS_PER_MILLI = 1_000_000L
