package io.customer.geofence.polygon

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.core.util.DispatchersProvider
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the high-accuracy stream used only while one or more polygon trigger circles are active. */
internal class PolygonLocationEngine(
    private val client: FusedLocationProviderClient,
    private val store: GeofenceRegionStore,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor,
    private val clock: Clock,
    dispatchersProvider: DispatchersProvider,
    private val logger: GeofenceLogger
) {
    private var routeProcessor = PolygonRouteProcessor(
        minimumEvidenceIntervalNanos = MINIMUM_EVIDENCE_INTERVAL_NANOS
    )
    private var sessionStartElapsedRealtimeNanos: Long? = null
    private val backgroundDispatcher = dispatchersProvider.background
    private var scopeJob: Job = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + backgroundDispatcher)
    private val cleanupScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private var locationCallback: LocationCallback? = null
    private var onUnavailableCallback: (() -> Unit)? = null
    private var samplingMode = PolygonSamplingMode.HIGH_ACCURACY
    private var registrationGeneration = 0L
    private var registrationRetryAttempt = 0
    private var registrationRetryJob: Job? = null
    private var noFixWatchdogJob: Job? = null
    private var lastLocationReceivedUptimeMs: Long? = null
    private var lastMotionFix: Location? = null
    private var lastMeaningfulMotionElapsedRealtimeNanos: Long? = null
    private val processingMutex = Mutex()
    private val stateLock = Any()
    private val geometryCache = mutableMapOf<String, CachedGeometry>()
    private val pendingLocationRemovals = mutableSetOf<LocationCallback>()
    private var cachedActiveIds: Set<String> = emptySet()
    private var cachedFences: List<PolygonFence> = emptyList()

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun start(onUnavailable: () -> Unit): Long? {
        if (store.getActivePolygonIds().isEmpty()) {
            onUnavailable()
            return null
        }
        val registration = synchronized(stateLock) {
            if (locationCallback != null) return registrationGeneration
            // A passive approach batch can arm this engine before the service reaches onCreate.
            // Preserve that observed session start; replacing it with the normal 30-second grace
            // would discard a valid delayed crossing. Cold recovery still has no session and arms
            // from the current trigger window.
            armSessionLocked(forceHighAccuracyBurst = sessionStartElapsedRealtimeNanos == null)
            ensureScopeLocked()
            samplingMode = PolygonSamplingMode.HIGH_ACCURACY
            onUnavailableCallback = onUnavailable
            createRegistrationLocked()
        }
        requestLocationUpdates(registration.callback, registration.generation)
        return registration.generation
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
            if (locationCallback !== callback || registrationGeneration != generation) return
            if (result.locations.isNotEmpty()) {
                lastLocationReceivedUptimeMs = android.os.SystemClock.elapsedRealtime()
            }
            scope
        }
        val userStateGeneration = store.userStateGeneration()
        callbackScope.launch {
            try {
                processLocations(result.locations, callback, generation, userStateGeneration)
                if (!isCurrentRegistration(callback, generation)) return@launch
                result.locations.maxByOrNull(Location::getElapsedRealtimeNanos)
                    ?.let(::updateSamplingMode)
                if (store.getActivePolygonIds().isEmpty()) onUnavailableCallback?.invoke()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.logPolygonMonitoringFailed(e.message)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(callback: LocationCallback, generation: Long) {
        val request = synchronized(stateLock) {
            if (locationCallback !== callback || registrationGeneration != generation) return
            locationRequest(samplingMode)
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnSuccessListener {
                    val wasStoppedBeforeRegistration = synchronized(stateLock) {
                        if (locationCallback === callback && registrationGeneration == generation) {
                            registrationRetryAttempt = 0
                            registrationRetryJob = null
                            lastLocationReceivedUptimeMs = android.os.SystemClock.elapsedRealtime()
                            startNoFixWatchdogLocked(callback, generation)
                            false
                        } else {
                            true
                        }
                    }
                    if (wasStoppedBeforeRegistration) {
                        removeLocationUpdatesReliably(callback)
                    }
                }
                .addOnFailureListener { cause ->
                    handleRegistrationFailure(callback, generation, cause)
                }
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
            val unavailable = synchronized(stateLock) { onUnavailableCallback }
            if (stopCurrent(callback, generation)) unavailable?.invoke()
            return
        }
        synchronized(stateLock) {
            if (locationCallback !== callback || registrationGeneration != generation) return
            noFixWatchdogJob?.cancel()
            registrationRetryJob?.cancel()
            registrationRetryAttempt += 1
            val exponent = (registrationRetryAttempt - 1).coerceAtMost(MAXIMUM_RETRY_EXPONENT)
            val retryDelayMs = (INITIAL_REGISTRATION_RETRY_MS * (1L shl exponent))
                .coerceAtMost(MAXIMUM_REGISTRATION_RETRY_MS)
            val retryScope = scope
            registrationRetryJob = retryScope.launch {
                delay(retryDelayMs)
                val shouldRetry = synchronized(stateLock) {
                    locationCallback === callback &&
                        registrationGeneration == generation &&
                        store.getActivePolygonIds().isNotEmpty()
                }
                if (shouldRetry) requestLocationUpdates(callback, generation)
            }
        }
    }

    private fun startNoFixWatchdogLocked(callback: LocationCallback, generation: Long) {
        noFixWatchdogJob?.cancel()
        val watchdogScope = scope
        noFixWatchdogJob = watchdogScope.launch {
            while (true) {
                delay(NO_FIX_WATCHDOG_INTERVAL_MS)
                val stalled = synchronized(stateLock) {
                    if (locationCallback !== callback || registrationGeneration != generation) {
                        return@launch
                    }
                    val lastFix = lastLocationReceivedUptimeMs ?: return@synchronized true
                    android.os.SystemClock.elapsedRealtime() - lastFix >= NO_FIX_WATCHDOG_INTERVAL_MS
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
            if (locationCallback !== callback || registrationGeneration != generation) return
            noFixWatchdogJob?.cancel()
            noFixWatchdogJob = null
            true
        }
        if (shouldRestart) requestLocationUpdates(callback, generation)
    }

    fun stop() {
        stopCurrent()
    }

    fun stopIfCurrent(expectedRegistrationGeneration: Long) {
        stopCurrent(expectedGeneration = expectedRegistrationGeneration)
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
            registrationGeneration += 1L
            registrationRetryAttempt = 0
            registrationRetryJob?.cancel()
            registrationRetryJob = null
            noFixWatchdogJob?.cancel()
            noFixWatchdogJob = null
            lastLocationReceivedUptimeMs = null
            samplingMode = PolygonSamplingMode.HIGH_ACCURACY
            routeProcessor.clear()
            geometryCache.clear()
            invalidateFenceCacheLocked()
            sessionStartElapsedRealtimeNanos = null
            lastMotionFix = null
            lastMeaningfulMotionElapsedRealtimeNanos = null
            scope.cancel()
            current
        }
        callback?.let { stoppedCallback ->
            removeLocationUpdatesReliably(stoppedCallback)
        }
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
                .addOnFailureListener { cause ->
                    retryLocationRemoval(callback, attempt, cause)
                }
        } catch (e: RuntimeException) {
            retryLocationRemoval(callback, attempt, e)
        }
    }

    private fun retryLocationRemoval(
        callback: LocationCallback,
        attempt: Int,
        cause: Throwable
    ) {
        logger.logPolygonMonitoringFailed(cause.message)
        val exponent = attempt.coerceAtMost(MAXIMUM_REMOVAL_RETRY_EXPONENT)
        val retryDelayMs = (INITIAL_REMOVAL_RETRY_MS * (1L shl exponent))
            .coerceAtMost(MAXIMUM_REMOVAL_RETRY_MS)
        cleanupScope.launch {
            delay(retryDelayMs)
            val stillPending = synchronized(stateLock) { callback in pendingLocationRemovals }
            if (stillPending) requestLocationRemoval(callback, attempt + 1)
        }
    }

    fun activate(polygonId: String) {
        synchronized(stateLock) {
            resetEvidenceLocked(polygonId)
            armSessionLocked(forceHighAccuracyBurst = true)
        }
        switchSamplingMode(PolygonSamplingMode.HIGH_ACCURACY)
    }

    /**
     * Arms evaluation from the fix that caused a passive approach session to begin. Background
     * delivery can batch recent locations, so using only the normal trigger grace would discard an
     * observed crossing merely because Play services delivered the batch late.
     */
    fun activateFromApproach(polygonId: String, firstFixElapsedRealtimeNanos: Long) {
        synchronized(stateLock) {
            resetEvidenceLocked(polygonId)
            armSessionLocked(
                forceHighAccuracyBurst = true,
                observedSessionStartElapsedRealtimeNanos = firstFixElapsedRealtimeNanos
            )
        }
        switchSamplingMode(PolygonSamplingMode.HIGH_ACCURACY)
    }

    fun resetEvidence(polygonId: String) = synchronized(stateLock) {
        resetEvidenceLocked(polygonId)
    }

    private fun resetEvidenceLocked(polygonId: String) {
        routeProcessor.clear(polygonId)
        geometryCache.remove(polygonId)
        invalidateFenceCacheLocked()
    }

    fun deactivate(polygonId: String) = synchronized(stateLock) {
        routeProcessor.clear(polygonId)
        geometryCache.remove(polygonId)
        invalidateFenceCacheLocked()
    }

    suspend fun processLocation(
        location: Location,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = processLocations(listOf(location), null, null, expectedUserStateGeneration)

    suspend fun processLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = processLocations(locations, null, null, expectedUserStateGeneration)

    private suspend fun processLocations(
        locations: List<Location>,
        expectedCallback: LocationCallback?,
        expectedGeneration: Long?,
        expectedUserStateGeneration: Long
    ) = processingMutex.withLock {
        if (locations.isEmpty()) return
        if (!isCurrentRegistration(expectedCallback, expectedGeneration)) return
        if (store.userStateGeneration() != expectedUserStateGeneration) return
        // Every active location batch is also an autonomous outbox-recovery opportunity. Do not
        // evaluate a newer edge while an older one is still unable to reach the durable file queue.
        if (!transitionProcessor.recoverPendingTransitions()) return
        if (!isCurrentRegistration(expectedCallback, expectedGeneration)) return
        if (store.userStateGeneration() != expectedUserStateGeneration) return
        val sessionArmed = synchronized(stateLock) {
            if (store.userStateGeneration() != expectedUserStateGeneration) {
                false
            } else {
                armSessionLocked()
                true
            }
        }
        if (!sessionArmed) return
        locations.sortedBy(Location::getElapsedRealtimeNanos).forEach { location ->
            if (!isCurrentRegistration(expectedCallback, expectedGeneration)) return@withLock
            if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
            val fix = location.toPolygonLocationFix() ?: return@forEach
            val detections = synchronized(stateLock) {
                if (store.userStateGeneration() != expectedUserStateGeneration) return@forEach
                if (!isCurrentSessionFixLocked(fix.elapsedRealtimeNanos)) return@forEach
                val fences = activePolygonFencesLocked()
                if (fences.isEmpty()) return@forEach
                val committedStates = store.getEnteredIds().associateWith { PolygonCommittedState.INSIDE }
                routeProcessor.process(
                    fences = fences,
                    sample = fix.sample,
                    elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
                    committedStates = committedStates
                )
            }
            detections.forEach { detection ->
                if (!isCurrentRegistration(expectedCallback, expectedGeneration)) return@withLock
                if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
                val transition = when (detection.transition) {
                    PolygonTransition.ENTER -> Event.GeofenceTransition.ENTER
                    PolygonTransition.EXIT -> Event.GeofenceTransition.EXIT
                }
                transitionProcessor.process(
                    geofenceId = detection.polygonId,
                    transition = transition,
                    timestampSeconds = observedTimestampSeconds(fix),
                    enforceConfiguredTransition = true,
                    expectedRegionRevision = detection.regionRevision,
                    expectedUserStateGeneration = expectedUserStateGeneration,
                    requireRegistered = true
                )
                if (!isCurrentRegistration(expectedCallback, expectedGeneration)) return@withLock
                if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
                if (detection.transition == PolygonTransition.EXIT) {
                    synchronized(stateLock) {
                        if (store.userStateGeneration() != expectedUserStateGeneration) {
                            return@withLock
                        }
                        if (
                            detection.polygonId !in store.getCoarseInsidePolygonIds() &&
                            detection.polygonId !in store.getEnteredIds() &&
                            store.deactivatePolygonIfCurrent(
                                detection.polygonId,
                                expectedUserStateGeneration
                            )
                        ) {
                            resetEvidenceLocked(detection.polygonId)
                        }
                    }
                }
            }
        }
    }

    private fun observedTimestampSeconds(fix: AndroidPolygonLocationFix): Long {
        val nowMillis = clock.currentTimeMillis()
        val monotonicAgeMillis =
            (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos).coerceAtLeast(0L) /
                NANOS_PER_MILLISECOND
        val monotonicTimestampMillis = (nowMillis - monotonicAgeMillis).coerceAtLeast(0L)
        val sourceTimestampMillis = fix.timestampMillis
        val earliestSaneSourceMillis =
            (monotonicTimestampMillis - MAX_SOURCE_CLOCK_DRIFT_MILLIS).coerceAtLeast(1L)
        val latestSaneSourceMillis = monotonicTimestampMillis + MAX_SOURCE_CLOCK_DRIFT_MILLIS
        val normalizedMillis = if (
            sourceTimestampMillis in earliestSaneSourceMillis..latestSaneSourceMillis
        ) {
            sourceTimestampMillis
        } else {
            monotonicTimestampMillis
        }
        return normalizedMillis / MILLIS_PER_SECOND
    }

    private fun activePolygonFencesLocked(): List<PolygonFence> {
        val activeIds = store.getActivePolygonIds()
        if (activeIds == cachedActiveIds) return cachedFences
        val regions = activeIds.mapNotNull(store::getCachedRegion).filter(GeofenceRegion::isPolygon)
        geometryCache.keys.retainAll(regions.mapTo(mutableSetOf()) { it.id })
        cachedActiveIds = activeIds
        cachedFences = regions.map { region ->
            val vertices = requireNotNull(region.polygonVertices)
            val cached = geometryCache[region.id]
            val geometry = if (cached?.vertices == vertices) {
                cached.geometry
            } else {
                PolygonGeometry.from(vertices).also {
                    geometryCache[region.id] = CachedGeometry(vertices, it)
                }
            }
            PolygonFence(region.id, geometry, region.transitionRevision())
        }
        return cachedFences
    }

    private fun invalidateFenceCacheLocked() {
        cachedActiveIds = emptySet()
        cachedFences = emptyList()
    }

    private fun armSessionLocked(
        forceHighAccuracyBurst: Boolean = false,
        observedSessionStartElapsedRealtimeNanos: Long? = null
    ) {
        if (sessionStartElapsedRealtimeNanos == null || forceHighAccuracyBurst) {
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            val normalTriggerStart = now - TRIGGER_LOCATION_GRACE_NANOS
            sessionStartElapsedRealtimeNanos = observedSessionStartElapsedRealtimeNanos
                ?.let { minOf(it, normalTriggerStart) }
                ?: normalTriggerStart
            lastMeaningfulMotionElapsedRealtimeNanos = now
        }
        ensureScopeLocked()
    }

    private fun updateSamplingMode(location: Location) {
        val desired = synchronized(stateLock) { desiredSamplingModeLocked(location) }
        switchSamplingMode(desired)
    }

    private fun desiredSamplingModeLocked(location: Location): PolygonSamplingMode {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val sessionStart = sessionStartElapsedRealtimeNanos ?: return PolygonSamplingMode.HIGH_ACCURACY
        val previous = lastMotionFix
        val reportedSpeed = location.speed.takeIf { location.hasSpeed() && it.isFinite() } ?: 0f
        val movedMeters = previous?.distanceTo(location) ?: 0f
        val meaningfulMovement = reportedSpeed >= MEANINGFUL_SPEED_METERS_PER_SECOND ||
            movedMeters >= MEANINGFUL_DISPLACEMENT_METERS
        if (meaningfulMovement) lastMeaningfulMotionElapsedRealtimeNanos = now
        lastMotionFix = Location(location)

        if (now - sessionStart <= HIGH_ACCURACY_BURST_NANOS) {
            return PolygonSamplingMode.HIGH_ACCURACY
        }
        val coordinate = PolygonCoordinate(location.latitude, location.longitude)
        val minimumBoundaryDistance = activePolygonFencesLocked().minOfOrNull {
            it.geometry.boundaryDistanceMeters(coordinate)
        } ?: return PolygonSamplingMode.BALANCED_POWER
        // Proximity outranks motion. A stationary device beside a narrow boundary can begin moving
        // and cross between balanced callbacks, so it must already be in high-accuracy mode.
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

    private fun switchSamplingMode(desired: PolygonSamplingMode) {
        val registration = synchronized(stateLock) {
            val callback = locationCallback ?: return
            if (samplingMode == desired) return
            samplingMode = desired
            noFixWatchdogJob?.cancel()
            noFixWatchdogJob = null
            LocationRegistration(callback, registrationGeneration)
        }
        // FLP replaces the previous request registered for the same callback. Reusing the callback
        // avoids a remove-then-add gap and cannot leak an ignored subscription if removal fails.
        requestLocationUpdates(registration.callback, registration.generation)
    }

    private fun isCurrentRegistration(
        expectedCallback: LocationCallback?,
        expectedGeneration: Long?
    ): Boolean {
        if (expectedCallback == null || expectedGeneration == null) return true
        return synchronized(stateLock) {
            locationCallback === expectedCallback && registrationGeneration == expectedGeneration
        }
    }

    private fun ensureScopeLocked() {
        if (scopeJob.isActive) return
        scopeJob = SupervisorJob()
        scope = CoroutineScope(scopeJob + backgroundDispatcher)
    }

    private fun isCurrentSessionFixLocked(elapsedRealtimeNanos: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val sessionStart = sessionStartElapsedRealtimeNanos ?: return false
        return elapsedRealtimeNanos >= sessionStart &&
            elapsedRealtimeNanos <= now + FUTURE_FIX_TOLERANCE_NANOS &&
            now - elapsedRealtimeNanos <= MAXIMUM_FIX_AGE_NANOS
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_SOURCE_CLOCK_DRIFT_MILLIS = 5 * 60 * 1_000L
        const val HIGH_ACCURACY_UPDATE_INTERVAL_MS = 2_000L
        const val HIGH_ACCURACY_MINIMUM_UPDATE_INTERVAL_MS = 1_000L
        const val HIGH_ACCURACY_MAXIMUM_BATCH_DELAY_MS = 5_000L
        const val BALANCED_UPDATE_INTERVAL_MS = 10_000L
        const val BALANCED_MINIMUM_UPDATE_INTERVAL_MS = 5_000L
        const val BALANCED_MAXIMUM_BATCH_DELAY_MS = 10_000L
        const val TRIGGER_LOCATION_GRACE_NANOS = 30_000_000_000L
        const val MAXIMUM_FIX_AGE_NANOS = 120_000_000_000L
        const val FUTURE_FIX_TOLERANCE_NANOS = 5_000_000_000L
        const val MINIMUM_EVIDENCE_INTERVAL_NANOS = 1_500_000_000L
        const val HIGH_ACCURACY_BURST_NANOS = 120_000_000_000L
        const val MOTION_MEMORY_NANOS = 60_000_000_000L
        const val MEANINGFUL_SPEED_METERS_PER_SECOND = 0.8f
        const val MEANINGFUL_DISPLACEMENT_METERS = 10f
        const val HIGH_ACCURACY_PROXIMITY_METERS = 500.0
        const val BALANCED_LOOK_AHEAD_SECONDS = 60.0
        const val INITIAL_REGISTRATION_RETRY_MS = 5_000L
        const val MAXIMUM_REGISTRATION_RETRY_MS = 300_000L
        const val MAXIMUM_RETRY_EXPONENT = 6
        const val INITIAL_REMOVAL_RETRY_MS = 1_000L
        const val MAXIMUM_REMOVAL_RETRY_MS = 60_000L
        const val MAXIMUM_REMOVAL_RETRY_EXPONENT = 6
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

    private data class CachedGeometry(
        val vertices: List<PolygonCoordinate>,
        val geometry: PolygonGeometry
    )

    private data class LocationRegistration(
        val callback: LocationCallback,
        val generation: Long
    )
}

internal enum class PolygonSamplingMode {
    HIGH_ACCURACY,
    BALANCED_POWER
}
