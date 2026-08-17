package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides polygon containment from the sparse fixes the *responsive* runtime already receives.
 *
 * ## What this engine is
 *
 * It never asks the OS for location. Every fix it sees was delivered for another reason: a
 * GMS enclosing-circle callback ([PolygonGeofenceServiceController.activate]/`onCoarseExit`) or a
 * low-power displacement-gated approach batch ([PolygonApproachMonitor]). The engine's job is to
 * decide, per fix, whether that single fix *alone* is decisive enough to move committed containment
 * ([PolygonEvidencePolicy.DECISIVE_SINGLE_FIX]).
 *
 * ## Best-effort boundary — read before relying on it
 *
 * This is deliberately low-power, and that has consequences it does not hide:
 *
 * - **Unobserved crossings are missed.** Between two approach fixes the device can enter and leave a
 *   polygon entirely. No fix means no evidence means no transition. The engine reports what it saw,
 *   not what happened.
 * - **A fix near the boundary decides nothing.** [PolygonAccuracyEvaluator.decisiveEvidenceFor]
 *   requires the whole accuracy circle, plus an anti-jitter margin, to be on one side of the ring.
 *   Fixes coarser than the decisive accuracy ceiling, and fixes whose uncertainty straddles the
 *   boundary, are ignored rather than guessed at — a wrong ENTER is worse than a late one.
 * - **Small polygons may never be decided at all.** A ring narrower than roughly twice
 *   (fix accuracy + margin) has no interior point far enough from its own boundary to be decisive.
 *   Such a polygon can only produce transitions from unusually accurate fixes, and on a device
 *   reporting typical background accuracy it will produce none. That is a reported limitation, not a
 *   defect to be tuned around here.
 *
 * A continuous high-accuracy stream — which is what closes those gaps, at a real battery cost — is
 * the opt-in [io.customer.geofence.PolygonTrackingMode.CONTINUOUS] runtime. It is owned by
 * [PolygonFineLocationStream], never by this engine: the engine still asks the OS for nothing. That
 * stream feeds whole batches to [processContinuousLocations] under
 * [PolygonEvidencePolicy.CONFIRMED], the multi-fix policy [PolygonRouteProcessor] already
 * implements. Every other source — coarse triggers and approach batches — keeps using
 * [processResponsiveLocation], in both modes, so opting in can only add evidence.
 */
internal class PolygonLocationEngine(
    private val store: GeofenceRegionStore,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor,
    private val clock: Clock,
    private val logger: GeofenceLogger
) {
    private val routeProcessor = PolygonRouteProcessor(
        minimumEvidenceIntervalNanos = MINIMUM_EVIDENCE_INTERVAL_NANOS
    )
    private var sessionStartElapsedRealtimeNanos: Long? = null
    private val processingMutex = Mutex()
    private val stateLock = Any()
    private val geometryCache = mutableMapOf<String, CachedGeometry>()
    private var cachedActiveIds: Set<String> = emptySet()
    private var cachedFences: List<PolygonFence> = emptyList()

    /**
     * Discards the current evaluation session. Called when no polygon is active any more, or when
     * user-scoped state is invalidated, so a later fix cannot be judged against a stale session.
     */
    fun stop() = synchronized(stateLock) {
        routeProcessor.clear()
        geometryCache.clear()
        invalidateFenceCacheLocked()
        sessionStartElapsedRealtimeNanos = null
    }

    fun activate(polygonId: String) = synchronized(stateLock) {
        resetEvidenceLocked(polygonId)
        armSessionLocked(restartSession = true)
    }

    /**
     * Arms evaluation from the fix that caused a passive approach session to begin. Background
     * delivery can batch recent locations, so using only the normal trigger grace would discard an
     * observed crossing merely because Play services delivered the batch late.
     */
    fun activateFromApproach(polygonId: String, firstFixElapsedRealtimeNanos: Long) =
        synchronized(stateLock) {
            resetEvidenceLocked(polygonId)
            armSessionLocked(
                restartSession = true,
                observedSessionStartElapsedRealtimeNanos = firstFixElapsedRealtimeNanos
            )
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
        resetEvidenceLocked(polygonId)
    }

    /**
     * Evaluates one fix that arrived from a low-power source, moving containment only when that
     * single fix is decisive on its own. See the class documentation for what this deliberately
     * cannot see.
     */
    suspend fun processResponsiveLocation(
        location: Location,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = processLocations(
        locations = listOf(location),
        expectedUserStateGeneration = expectedUserStateGeneration,
        evidencePolicy = PolygonEvidencePolicy.DECISIVE_SINGLE_FIX
    )

    /**
     * Ordered evaluation of a whole high-accuracy batch under the multi-fix
     * [PolygonEvidencePolicy.CONFIRMED] policy.
     *
     * Only [PolygonFineLocationStream] may call this, and only from a stream registration that
     * Play services has accepted: CONFIRMED needs several fixes seconds apart to move containment,
     * which is a worse trade than [processResponsiveLocation] for anything sparser. Nothing else
     * in the runtime hands fixes here, so a denied or failed foreground promotion simply leaves
     * every fix on the responsive path.
     */
    suspend fun processContinuousLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long
    ) = processLocations(
        locations = locations,
        expectedUserStateGeneration = expectedUserStateGeneration,
        evidencePolicy = PolygonEvidencePolicy.CONFIRMED
    )

    /**
     * Metres from the given coordinate to the nearest active polygon boundary, or `null` when no
     * active polygon has usable geometry. Lets [PolygonFineLocationStream] relax its sampling rate
     * far from every boundary without duplicating the geometry the engine already caches.
     */
    fun minimumBoundaryDistanceMeters(latitude: Double, longitude: Double): Double? =
        synchronized(stateLock) {
            val coordinate = PolygonCoordinate(latitude, longitude)
            activePolygonFencesLocked().minOfOrNull {
                it.geometry.boundaryDistanceMeters(coordinate)
            }
        }

    /** Ordered evaluation of [locations] under [evidencePolicy]. */
    private suspend fun processLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long,
        evidencePolicy: PolygonEvidencePolicy
    ) = processingMutex.withLock {
        if (locations.isEmpty()) return
        if (store.userStateGeneration() != expectedUserStateGeneration) return
        // Every active location batch is also an autonomous outbox-recovery opportunity. Do not
        // evaluate a newer edge while an older one is still unable to reach the durable file queue.
        if (!transitionProcessor.recoverPendingTransitions()) return
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
            if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
            val fix = location.toPolygonLocationFix()
            if (fix == null) {
                logger.logPolygonFixNotUsable("it carries no usable accuracy or monotonic timestamp")
                return@forEach
            }
            val detections = synchronized(stateLock) {
                if (store.userStateGeneration() != expectedUserStateGeneration) return@forEach
                if (!isCurrentSessionFixLocked(fix.elapsedRealtimeNanos)) {
                    logger.logPolygonFixNotUsable("it predates the current evaluation session or is too old")
                    return@forEach
                }
                val fences = activePolygonFencesLocked()
                if (fences.isEmpty()) return@forEach
                val committedStates = store.getEnteredIds().associateWith { PolygonCommittedState.INSIDE }
                routeProcessor.process(
                    fences = fences,
                    sample = fix.sample,
                    elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
                    committedStates = committedStates,
                    evidencePolicy = evidencePolicy
                )
            }
            detections.forEach { detection ->
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
        cachedFences = regions.mapNotNull { region ->
            val vertices = requireNotNull(region.polygonVertices)
            val cached = geometryCache[region.id]
            val geometry = if (cached != null && cached.vertices == vertices) {
                cached.geometry
            } else {
                PolygonGeometry.fromOrNull(vertices).also {
                    geometryCache[region.id] = CachedGeometry(vertices, it)
                }
            }
            if (geometry == null) {
                // A ring that no longer validates must not fall back to its enclosing circle: that
                // circle is a coarse trigger, kilometres wider than the fence.
                logger.logPolygonRegionNotRanked(region.id, "the cached ring no longer validates")
                null
            } else {
                PolygonFence(region.id, geometry, region.transitionRevision())
            }
        }
        return cachedFences
    }

    private fun invalidateFenceCacheLocked() {
        cachedActiveIds = emptySet()
        cachedFences = emptyList()
    }

    private fun armSessionLocked(
        restartSession: Boolean = false,
        observedSessionStartElapsedRealtimeNanos: Long? = null
    ) {
        if (sessionStartElapsedRealtimeNanos != null && !restartSession) return
        val now = SystemClock.elapsedRealtimeNanos()
        val normalTriggerStart = now - TRIGGER_LOCATION_GRACE_NANOS
        sessionStartElapsedRealtimeNanos = observedSessionStartElapsedRealtimeNanos
            ?.let { minOf(it, normalTriggerStart) }
            ?: normalTriggerStart
    }

    private fun isCurrentSessionFixLocked(elapsedRealtimeNanos: Long): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        val sessionStart = sessionStartElapsedRealtimeNanos ?: return false
        return elapsedRealtimeNanos >= sessionStart &&
            elapsedRealtimeNanos <= now + FUTURE_FIX_TOLERANCE_NANOS &&
            now - elapsedRealtimeNanos <= MAXIMUM_FIX_AGE_NANOS
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_SOURCE_CLOCK_DRIFT_MILLIS = 5 * 60 * 1_000L

        // Only CONFIRMED evidence is debounced. Two fixes a few hundred milliseconds apart describe
        // one moment, not two independent observations, so a dense stream must not confirm from them.
        const val MINIMUM_EVIDENCE_INTERVAL_NANOS = 1_500_000_000L
        const val TRIGGER_LOCATION_GRACE_NANOS = 30_000_000_000L
        const val MAXIMUM_FIX_AGE_NANOS = 120_000_000_000L
        const val FUTURE_FIX_TOLERANCE_NANOS = 5_000_000_000L
    }

    private data class CachedGeometry(
        val vertices: List<PolygonCoordinate>,
        val geometry: PolygonGeometry?
    )
}
