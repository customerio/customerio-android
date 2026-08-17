package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.store.GeofenceRegionStore
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The high-accuracy stream behind [io.customer.geofence.PolygonTrackingMode.CONTINUOUS].
 *
 * It exists only while [PolygonLocationService] holds a promoted location foreground service, and
 * it is the sole producer of [PolygonEvidencePolicy.CONFIRMED] evidence: batches reach
 * [PolygonLocationEngine.processContinuousLocations] from [handleLocationResult] and from nowhere
 * else. That containment is the reliability contract of the opt-in — if this stream is never
 * registered, or is torn down, every fix the SDK still receives keeps flowing through the
 * responsive path, so continuous mode degrades to responsive mode instead of below it.
 *
 * ## Lifecycle
 *
 * [start] returns a registration generation the caller quotes back to [stopIfCurrent]; every
 * asynchronous continuation re-checks that generation under [stateLock] before acting. A stop
 * bumps it, so a registration that Play services accepts after sign-out, an identity switch, a
 * mode switch or a catalog removal finds itself stale and removes its own updates rather than
 * resurrecting fine sampling. [isReady] is true only between an accepted registration and its stop.
 */
internal class PolygonFineLocationStream(
    private val client: FusedLocationProviderClient,
    private val engine: PolygonLocationEngine,
    private val store: GeofenceRegionStore,
    private val logger: GeofenceLogger,
    private val backgroundContext: CoroutineContext
) {
    private val stateLock = Any()
    private var scopeJob: Job = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + backgroundContext)
    private val cleanupScope = CoroutineScope(SupervisorJob() + backgroundContext)
    private var locationCallback: LocationCallback? = null
    private var onUnavailableCallback: (() -> Unit)? = null
    private var registrationGeneration = 0L
    private var registrationAccepted = false
    private var samplingMode = PolygonSamplingMode.HIGH_ACCURACY
    private var registrationRetryAttempt = 0
    private var registrationRetryJob: Job? = null
    private var noFixWatchdogJob: Job? = null
    private var lastLocationReceivedElapsedMs: Long? = null
    private var lastMotionFix: Location? = null
    private var lastMeaningfulMotionElapsedRealtimeNanos: Long? = null
    private val pendingLocationRemovals = mutableSetOf<LocationCallback>()

    /**
     * Registers the stream, or returns `null` when there is nothing to sample for. Returning the
     * existing generation for an already-registered stream keeps a redelivered service start from
     * churning the registration.
     *
     * Deliberately not `@RequiresPermission`: the caller is a service command whose permission can
     * be revoked between its own check and this call. A [SecurityException] here is a handled
     * outcome — the stream stops and [onUnavailable] runs — not a caller contract.
     */
    fun start(onUnavailable: () -> Unit): Long? {
        if (store.getActivePolygonIds().isEmpty()) return null
        val registration = synchronized(stateLock) {
            if (locationCallback != null) {
                onUnavailableCallback = onUnavailable
                return registrationGeneration
            }
            ensureScopeLocked()
            samplingMode = PolygonSamplingMode.HIGH_ACCURACY
            registrationAccepted = false
            lastMotionFix = null
            lastMeaningfulMotionElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            onUnavailableCallback = onUnavailable
            createRegistrationLocked()
        }
        requestLocationUpdates(registration.callback, registration.generation)
        return registration.generation
    }

    /** Whether Play services has accepted a registration that has not since been stopped. */
    fun isReady(): Boolean = synchronized(stateLock) {
        locationCallback != null && registrationAccepted
    }

    fun stop() {
        stopCurrent()
    }

    /** Stops only the registration [expectedRegistrationGeneration] describes. */
    fun stopIfCurrent(expectedRegistrationGeneration: Long) {
        stopCurrent(expectedGeneration = expectedRegistrationGeneration)
    }

    private fun createRegistrationLocked(): LocationRegistration {
        registrationGeneration += 1L
        val generation = registrationGeneration
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                handleLocationResult(this, generation, result)
            }
        }
        locationCallback = callback
        return LocationRegistration(callback, generation)
    }

    private fun handleLocationResult(
        callback: LocationCallback,
        generation: Long,
        result: LocationResult
    ) {
        val callbackScope = synchronized(stateLock) {
            if (!isCurrentRegistrationLocked(callback, generation)) return
            if (result.locations.isNotEmpty()) {
                lastLocationReceivedElapsedMs = SystemClock.elapsedRealtime()
            }
            scope
        }
        val userStateGeneration = store.userStateGeneration()
        callbackScope.launch {
            try {
                engine.processContinuousLocations(result.locations, userStateGeneration)
                if (!isCurrentRegistration(callback, generation)) return@launch
                result.locations.maxByOrNull(Location::getElapsedRealtimeNanos)
                    ?.let { updateSamplingMode(it, callback, generation) }
                if (store.getActivePolygonIds().isEmpty()) {
                    synchronized(stateLock) {
                        onUnavailableCallback.takeIf { isCurrentRegistrationLocked(callback, generation) }
                    }?.invoke()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.logPolygonMonitoringFailed(e.message)
            }
        }
    }

    /**
     * The one statement in this class that can throw [SecurityException]. The suppression covers
     * exactly it, because the missing-permission case is handled rather than ignored: both the
     * synchronous throw and the asynchronous failure route to [handleRegistrationFailure].
     */
    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(callback: LocationCallback, generation: Long) {
        val request = synchronized(stateLock) {
            if (!isCurrentRegistrationLocked(callback, generation)) return
            locationRequest(samplingMode)
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnSuccessListener {
                    val stale = synchronized(stateLock) {
                        if (isCurrentRegistrationLocked(callback, generation)) {
                            registrationAccepted = true
                            registrationRetryAttempt = 0
                            registrationRetryJob = null
                            lastLocationReceivedElapsedMs = SystemClock.elapsedRealtime()
                            startNoFixWatchdogLocked(callback, generation)
                            false
                        } else {
                            true
                        }
                    }
                    // A stop that landed while Play services was still accepting this request has
                    // already bumped the generation, so the accepted registration belongs to nobody.
                    if (stale) {
                        removeLocationUpdatesReliably(callback)
                    } else {
                        logger.logPolygonContinuousMonitoringStarted()
                    }
                }
                .addOnFailureListener { cause -> handleRegistrationFailure(callback, generation, cause) }
        } catch (e: RuntimeException) {
            handleRegistrationFailure(callback, generation, e)
        }
    }

    private fun handleRegistrationFailure(
        callback: LocationCallback,
        generation: Long,
        cause: Throwable
    ) {
        logger.logPolygonMonitoringFailed(cause.message)
        if (cause is SecurityException) {
            // Nothing to retry: the permission is gone, and the responsive path already owns every
            // fix this stream would have carried.
            val unavailable = synchronized(stateLock) { onUnavailableCallback }
            if (stopCurrent(callback, generation)) unavailable?.invoke()
            return
        }
        synchronized(stateLock) {
            if (!isCurrentRegistrationLocked(callback, generation)) return
            registrationAccepted = false
            noFixWatchdogJob?.cancel()
            noFixWatchdogJob = null
            registrationRetryJob?.cancel()
            registrationRetryAttempt += 1
            val exponent = (registrationRetryAttempt - 1).coerceAtMost(MAXIMUM_RETRY_EXPONENT)
            val retryDelayMs = (INITIAL_REGISTRATION_RETRY_MS * (1L shl exponent))
                .coerceAtMost(MAXIMUM_REGISTRATION_RETRY_MS)
            registrationRetryJob = scope.launch {
                delay(retryDelayMs)
                val shouldRetry = synchronized(stateLock) {
                    isCurrentRegistrationLocked(callback, generation) &&
                        store.getActivePolygonIds().isNotEmpty()
                }
                if (shouldRetry) requestLocationUpdates(callback, generation)
            }
        }
    }

    private fun startNoFixWatchdogLocked(callback: LocationCallback, generation: Long) {
        noFixWatchdogJob?.cancel()
        noFixWatchdogJob = scope.launch {
            while (true) {
                delay(NO_FIX_WATCHDOG_INTERVAL_MS)
                val stalled = synchronized(stateLock) {
                    if (!isCurrentRegistrationLocked(callback, generation)) return@launch
                    val lastFix = lastLocationReceivedElapsedMs ?: return@synchronized true
                    SystemClock.elapsedRealtime() - lastFix >= NO_FIX_WATCHDOG_INTERVAL_MS
                }
                if (stalled) {
                    restartRegistration(callback, generation)
                    return@launch
                }
            }
        }
    }

    private fun restartRegistration(callback: LocationCallback, generation: Long) {
        val shouldRestart = synchronized(stateLock) {
            if (!isCurrentRegistrationLocked(callback, generation)) return
            noFixWatchdogJob?.cancel()
            noFixWatchdogJob = null
            true
        }
        if (shouldRestart) requestLocationUpdates(callback, generation)
    }

    private fun stopCurrent(
        expectedCallback: LocationCallback? = null,
        expectedGeneration: Long? = null
    ): Boolean {
        val callback = synchronized(stateLock) {
            if (expectedCallback != null && locationCallback !== expectedCallback) return false
            if (expectedGeneration != null && registrationGeneration != expectedGeneration) return false
            val current = locationCallback
            locationCallback = null
            onUnavailableCallback = null
            registrationAccepted = false
            // Bumped even when nothing was registered: a request still in flight inside Play
            // services must not be able to claim this generation once it returns.
            registrationGeneration += 1L
            registrationRetryAttempt = 0
            registrationRetryJob?.cancel()
            registrationRetryJob = null
            noFixWatchdogJob?.cancel()
            noFixWatchdogJob = null
            lastLocationReceivedElapsedMs = null
            lastMotionFix = null
            lastMeaningfulMotionElapsedRealtimeNanos = null
            samplingMode = PolygonSamplingMode.HIGH_ACCURACY
            // Cancels in-flight batch processing too, so a fix decoded before the stop cannot commit
            // a transition after it. Removal runs on the separate cleanup scope.
            scope.cancel()
            current
        }
        callback?.let(::removeLocationUpdatesReliably)
        return true
    }

    private fun removeLocationUpdatesReliably(callback: LocationCallback) {
        val shouldStart = synchronized(stateLock) { pendingLocationRemovals.add(callback) }
        if (shouldStart) requestLocationRemoval(callback, attempt = 0)
    }

    private fun requestLocationRemoval(callback: LocationCallback, attempt: Int) {
        try {
            client.removeLocationUpdates(callback)
                .addOnSuccessListener {
                    synchronized(stateLock) { pendingLocationRemovals.remove(callback) }
                }
                .addOnFailureListener { cause -> retryLocationRemoval(callback, attempt, cause) }
        } catch (e: RuntimeException) {
            retryLocationRemoval(callback, attempt, e)
        }
    }

    private fun retryLocationRemoval(callback: LocationCallback, attempt: Int, cause: Throwable) {
        logger.logPolygonMonitoringFailed(cause.message)
        val exponent = attempt.coerceAtMost(MAXIMUM_RETRY_EXPONENT)
        val retryDelayMs = (INITIAL_REMOVAL_RETRY_MS * (1L shl exponent))
            .coerceAtMost(MAXIMUM_REMOVAL_RETRY_MS)
        cleanupScope.launch {
            delay(retryDelayMs)
            val stillPending = synchronized(stateLock) { callback in pendingLocationRemovals }
            if (stillPending) requestLocationRemoval(callback, attempt + 1)
        }
    }

    /**
     * Drops to balanced power only while every active boundary is far away and the device has not
     * moved meaningfully for a while. Proximity outranks motion: a stationary device beside a
     * narrow boundary can start moving and cross between two balanced callbacks.
     */
    private fun updateSamplingMode(
        location: Location,
        callback: LocationCallback,
        generation: Long
    ) {
        val desired = synchronized(stateLock) {
            if (!isCurrentRegistrationLocked(callback, generation)) return
            desiredSamplingModeLocked(location)
        }
        switchSamplingMode(desired, callback, generation)
    }

    private fun desiredSamplingModeLocked(location: Location): PolygonSamplingMode {
        val now = SystemClock.elapsedRealtimeNanos()
        val reportedSpeed = location.speed.takeIf { location.hasSpeed() && it.isFinite() } ?: 0f
        val movedMeters = lastMotionFix?.distanceTo(location) ?: 0f
        val meaningfulMovement = reportedSpeed >= MEANINGFUL_SPEED_METERS_PER_SECOND ||
            movedMeters >= MEANINGFUL_DISPLACEMENT_METERS
        if (meaningfulMovement) lastMeaningfulMotionElapsedRealtimeNanos = now
        lastMotionFix = Location(location)

        val minimumBoundaryDistance = engine
            .minimumBoundaryDistanceMeters(location.latitude, location.longitude)
            ?: return PolygonSamplingMode.BALANCED_POWER
        if (minimumBoundaryDistance <= HIGH_ACCURACY_PROXIMITY_METERS) {
            return PolygonSamplingMode.HIGH_ACCURACY
        }
        val lastMotion = lastMeaningfulMotionElapsedRealtimeNanos
        val recentlyMoving = lastMotion != null && now - lastMotion <= MOTION_MEMORY_NANOS
        if (!recentlyMoving) return PolygonSamplingMode.BALANCED_POWER

        val lookAheadMeters = max(
            HIGH_ACCURACY_PROXIMITY_METERS,
            reportedSpeed * BALANCED_LOOK_AHEAD_SECONDS + location.accuracy * 2.0
        )
        return if (minimumBoundaryDistance <= lookAheadMeters) {
            PolygonSamplingMode.HIGH_ACCURACY
        } else {
            PolygonSamplingMode.BALANCED_POWER
        }
    }

    private fun switchSamplingMode(
        desired: PolygonSamplingMode,
        callback: LocationCallback,
        generation: Long
    ) {
        synchronized(stateLock) {
            if (!isCurrentRegistrationLocked(callback, generation)) return
            if (samplingMode == desired) return
            samplingMode = desired
            noFixWatchdogJob?.cancel()
            noFixWatchdogJob = null
        }
        // Play services replaces the previous request registered for the same callback. Reusing the
        // callback avoids a remove-then-add gap and cannot leak an ignored subscription if a
        // removal fails.
        requestLocationUpdates(callback, generation)
    }

    private fun isCurrentRegistration(callback: LocationCallback, generation: Long): Boolean =
        synchronized(stateLock) { isCurrentRegistrationLocked(callback, generation) }

    private fun isCurrentRegistrationLocked(callback: LocationCallback, generation: Long): Boolean =
        locationCallback === callback && registrationGeneration == generation

    private fun ensureScopeLocked() {
        if (scopeJob.isActive) return
        scopeJob = SupervisorJob()
        scope = CoroutineScope(scopeJob + backgroundContext)
    }

    private data class LocationRegistration(
        val callback: LocationCallback,
        val generation: Long
    )

    private companion object {
        const val HIGH_ACCURACY_UPDATE_INTERVAL_MS = 2_000L
        const val HIGH_ACCURACY_MINIMUM_UPDATE_INTERVAL_MS = 1_000L
        const val HIGH_ACCURACY_MAXIMUM_BATCH_DELAY_MS = 5_000L
        const val BALANCED_UPDATE_INTERVAL_MS = 10_000L
        const val BALANCED_MINIMUM_UPDATE_INTERVAL_MS = 5_000L
        const val BALANCED_MAXIMUM_BATCH_DELAY_MS = 10_000L
        const val MOTION_MEMORY_NANOS = 60_000_000_000L
        const val MEANINGFUL_SPEED_METERS_PER_SECOND = 0.8f
        const val MEANINGFUL_DISPLACEMENT_METERS = 10f
        const val HIGH_ACCURACY_PROXIMITY_METERS = 500.0
        const val BALANCED_LOOK_AHEAD_SECONDS = 60.0
        const val INITIAL_REGISTRATION_RETRY_MS = 5_000L
        const val MAXIMUM_REGISTRATION_RETRY_MS = 300_000L
        const val INITIAL_REMOVAL_RETRY_MS = 1_000L
        const val MAXIMUM_REMOVAL_RETRY_MS = 60_000L
        const val MAXIMUM_RETRY_EXPONENT = 6
        const val NO_FIX_WATCHDOG_INTERVAL_MS = 90_000L

        fun locationRequest(mode: PolygonSamplingMode): LocationRequest = when (mode) {
            PolygonSamplingMode.HIGH_ACCURACY -> LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                HIGH_ACCURACY_UPDATE_INTERVAL_MS
            )
                .setMinUpdateIntervalMillis(HIGH_ACCURACY_MINIMUM_UPDATE_INTERVAL_MS)
                .setMaxUpdateDelayMillis(HIGH_ACCURACY_MAXIMUM_BATCH_DELAY_MS)
                .setWaitForAccurateLocation(false)
                .build()
            PolygonSamplingMode.BALANCED_POWER -> LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                BALANCED_UPDATE_INTERVAL_MS
            )
                .setMinUpdateIntervalMillis(BALANCED_MINIMUM_UPDATE_INTERVAL_MS)
                .setMaxUpdateDelayMillis(BALANCED_MAXIMUM_BATCH_DELAY_MS)
                .setMinUpdateDistanceMeters(MEANINGFUL_DISPLACEMENT_METERS)
                .setWaitForAccurateLocation(false)
                .build()
        }
    }
}

internal enum class PolygonSamplingMode {
    HIGH_ACCURACY,
    BALANCED_POWER
}
