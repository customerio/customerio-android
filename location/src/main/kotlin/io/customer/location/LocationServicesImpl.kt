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

    override fun setLastKnownLocation(location: Location) {
        // Unlike the coordinate overload, this one carries when the host's fix was taken and how
        // accurate it was. Accuracy rides only with the fix's own boot-clock stamp: a time estimated
        // from the wall clock still ages the fix, but a clock step can make an old fix read as
        // current, so it must not be able to prove the device outside a fence.
        val monotonicFixMillis = location.monotonicFixMillis()
        trackHostLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            fixElapsedRealtimeMillis = monotonicFixMillis ?: location.wallClockFixElapsedMillis(),
            horizontalAccuracyMeters = monotonicFixMillis?.let { location.reportedAccuracyMeters() }
        )
    }

    private fun trackHostLocation(
        latitude: Double,
        longitude: Double,
        fixElapsedRealtimeMillis: Long? = null,
        horizontalAccuracyMeters: Float? = null
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
            latitude = latitude,
            longitude = longitude,
            fixElapsedRealtimeMillis = fixElapsedRealtimeMillis,
            horizontalAccuracyMeters = horizontalAccuracyMeters
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

/** The fix's own boot-clock stamp, or null when the host set none. */
private fun Location.monotonicFixMillis(): Long? =
    elapsedRealtimeNanos.takeIf { it > 0L }?.let { it / NANOS_PER_MILLI }

/**
 * A host-built [Location] often stamps only wall-clock [Location.time]. Mapped onto the boot clock so
 * an old host fix is still aged rather than trusted as current. Only an estimate: a wall-clock step
 * since the fix shifts it by the step, which is why such a fix is forwarded without its accuracy.
 */
private fun Location.wallClockFixElapsedMillis(): Long? =
    time.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - (System.currentTimeMillis() - it) }

/**
 * [Location.getAccuracy] reads 0 when none was set, which would pass for a perfect fix and let any
 * host fix just past a fence's edge prove the device outside. Null unless reported, finite and >= 0.
 */
private fun Location.reportedAccuracyMeters(): Float? =
    if (hasAccuracy()) accuracy.takeIf { it.isFinite() && it >= 0f } else null

private const val NANOS_PER_MILLI = 1_000_000L
