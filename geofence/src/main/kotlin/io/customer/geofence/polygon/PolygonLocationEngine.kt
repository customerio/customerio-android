package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceLogTail
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.PolygonEvaluationSkip
import io.customer.geofence.PolygonFixRejection
import io.customer.geofence.PolygonNotRankedReason
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What one evaluation pass concluded, beyond the transitions it already committed.
 *
 * [undecidedPolygonIds] and [pendingArrivalPolygonIds] cue the caller to request a better fix:
 * those fences were judged but the fix did not separate inside from outside, or they are holding a
 * marginal ENTER. An aborted pass reports neither, so an identify landing mid-pass triggers no
 * sensor request for a session that is gone. [pendingArrivalPolygonIds] accumulates over the pass,
 * so it means "still holding" only for a single-location pass, which is all any caller passes.
 *
 * [evaluatedPolygonIds] is every fence actually judged. A fence skipped because the fix is not
 * newer than its last one is in neither set, so absence from [undecidedPolygonIds] is not a
 * decision. [acceptedFix] is set once per pass and cannot answer that.
 */
internal data class PolygonEvaluationOutcome(
    val acceptedFix: Boolean,
    val undecidedPolygonIds: Set<String>,
    val pendingArrivalPolygonIds: Set<String> = emptySet(),
    val evaluatedPolygonIds: Set<String> = emptySet()
) {
    internal companion object {
        val NOTHING = PolygonEvaluationOutcome(acceptedFix = false, undecidedPolygonIds = emptySet())
    }
}

/**
 * Decides polygon containment from fixes admitted by the wake-scoped responsive runtime.
 *
 * It never requests location itself: fixes come from GMS wake callbacks, the bounded
 * [PolygonApproachMonitor] session, or a precise fix its callers request. Best-effort by design:
 * a crossing no wake observes with a usable fix is missed, and a fix too coarse for the venue or
 * too close to the ring decides nothing on its own (see [PolygonAccuracyEvaluator]). V1 has no
 * continuous or foreground-service mode.
 */
internal class PolygonLocationEngine(
    private val store: GeofenceRegionStore,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor,
    private val clock: Clock,
    private val logger: GeofenceLogger
) {
    private val routeProcessor = PolygonRouteProcessor()
    private var sessionStartElapsedRealtimeNanos: Long? = null
    private val processingMutex = Mutex()
    private val stateLock = Any()
    private val geometryCache = mutableMapOf<String, CachedGeometry>()

    // Keyed on each fence's geometry, not the active id set: a sync can replace a ring without
    // changing which polygons are active.
    private var cachedFenceSignature: Map<String, Int> = emptyMap()
    private var cachedFences: List<PolygonFence> = emptyList()

    /**
     * Discards the current evaluation session. Called when no polygon is active any more, or when
     * user-scoped state is invalidated, so a later fix cannot be judged against a stale session.
     */
    fun stop(): Set<String> = synchronized(stateLock) {
        routeProcessor.clear().also {
            geometryCache.clear()
            invalidateFenceCacheLocked()
            sessionStartElapsedRealtimeNanos = null
        }
    }

    fun activate(polygonId: String): Set<String> = synchronized(stateLock) {
        resetEvidenceLocked(polygonId).also { armSessionLocked(restartSession = true) }
    }

    /**
     * Arms evaluation from the fix that caused a passive approach session to begin. Background
     * delivery can batch recent locations, so using only the normal trigger grace would discard an
     * observed crossing merely because Play services delivered the batch late.
     */
    fun activateFromApproach(
        polygonId: String,
        firstFixElapsedRealtimeNanos: Long
    ): Set<String> = synchronized(stateLock) {
        resetEvidenceLocked(polygonId).also {
            armSessionLocked(
                restartSession = true,
                observedSessionStartElapsedRealtimeNanos = firstFixElapsedRealtimeNanos
            )
        }
    }

    fun resetEvidence(polygonId: String): Set<String> =
        synchronized(stateLock) { resetEvidenceLocked(polygonId) }

    /**
     * Whether the calling thread holds [stateLock], so a test can assert no record reaches the
     * host's log dispatcher under it.
     */
    @VisibleForTesting
    internal fun holdsStateLock(): Boolean = Thread.holdsLock(stateLock)

    /** The one place a returned [PolygonRouteRecord] becomes a log line. */
    private fun emitRouteRecord(record: PolygonRouteRecord) = when (record) {
        is PolygonRouteRecord.Undecided -> logger.logPolygonUndecided(
            geofenceId = record.geofenceId,
            reason = record.reason,
            signedBoundaryDistanceMeters = record.signedBoundaryDistanceMeters,
            horizontalAccuracyMeters = record.horizontalAccuracyMeters,
            fixAgeSeconds = record.fixAgeSeconds
        )
        is PolygonRouteRecord.Unchanged -> logger.logPolygonUnchanged(
            geofenceId = record.geofenceId,
            membership = record.membership,
            signedBoundaryDistanceMeters = record.signedBoundaryDistanceMeters,
            horizontalAccuracyMeters = record.horizontalAccuracyMeters,
            fixAgeSeconds = record.fixAgeSeconds
        )
        is PolygonRouteRecord.Decided -> logger.logPolygonDecided(
            geofenceId = record.geofenceId,
            transitionName = record.transitionName,
            signedBoundaryDistanceMeters = record.signedBoundaryDistanceMeters,
            horizontalAccuracyMeters = record.horizontalAccuracyMeters,
            fixAgeSeconds = record.fixAgeSeconds,
            corroborated = record.corroborated,
            uncorroboratedReason = record.uncorroboratedReason
        )
        is PolygonRouteRecord.ArrivalPending -> logger.logPolygonArrivalPending(
            geofenceId = record.geofenceId,
            signedBoundaryDistanceMeters = record.signedBoundaryDistanceMeters,
            horizontalAccuracyMeters = record.horizontalAccuracyMeters,
            fixAgeSeconds = record.fixAgeSeconds
        )
        is PolygonRouteRecord.ArrivalExpired -> logger.logPolygonArrivalExpired(
            geofenceId = record.geofenceId,
            reason = record.reason,
            heldForSeconds = record.heldForSeconds
        )
    }

    private fun resetEvidenceLocked(polygonId: String): Set<String> {
        val wasPending = routeProcessor.clear(polygonId)
        geometryCache.remove(polygonId)
        invalidateFenceCacheLocked()
        return if (wasPending) setOf(polygonId) else emptySet()
    }

    fun deactivate(polygonId: String): Set<String> =
        synchronized(stateLock) { resetEvidenceLocked(polygonId) }

    /** Evaluates one fix from a low-power source; see the class KDoc for what this cannot observe. */
    suspend fun processResponsiveLocation(
        location: Location,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        answersHeldFixAt: Long? = null
    ): PolygonEvaluationOutcome = processLocations(
        locations = listOf(location),
        expectedUserStateGeneration = expectedUserStateGeneration,
        answersHeldFixAt = answersHeldFixAt
    )

    /** Ordered evaluation of a batch of fixes; called only by [processResponsiveLocation], one at a time. */
    private suspend fun processLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long,
        answersHeldFixAt: Long?
    ): PolygonEvaluationOutcome = processingMutex.withLock {
        if (locations.isEmpty()) return@withLock PolygonEvaluationOutcome.NOTHING
        if (store.userStateGeneration() != expectedUserStateGeneration) {
            logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
            return@withLock PolygonEvaluationOutcome.NOTHING
        }
        // Every active location batch is also an autonomous outbox-recovery opportunity. Do not
        // evaluate a newer edge while an older one is still unable to reach the durable file queue.
        if (!transitionProcessor.recoverPendingTransitions()) {
            logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.OUTBOX_BLOCKED)
            return@withLock PolygonEvaluationOutcome.NOTHING
        }
        if (store.userStateGeneration() != expectedUserStateGeneration) {
            logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
            return@withLock PolygonEvaluationOutcome.NOTHING
        }
        val sessionArmed = synchronized(stateLock) {
            if (store.userStateGeneration() != expectedUserStateGeneration) {
                false
            } else {
                armSessionLocked()
                true
            }
        }
        if (!sessionArmed) {
            // armSessionLocked cannot fail, so only the generation check above reaches here.
            logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
            return@withLock PolygonEvaluationOutcome.NOTHING
        }
        var acceptedFix = false
        val undecidedPolygonIds = mutableSetOf<String>()
        val pendingArrivalPolygonIds = mutableSetOf<String>()
        val evaluatedPolygonIds = mutableSetOf<String>()
        for (location in locations.sortedBy(Location::getElapsedRealtimeNanos)) {
            if (store.userStateGeneration() != expectedUserStateGeneration) {
                logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
                return@withLock PolygonEvaluationOutcome.NOTHING
            }
            val fix = location.toPolygonLocationFix()
            if (fix == null) {
                logger.logPolygonFixNotUsable(PolygonFixRejection.NO_USABLE_FIX)
                continue
            }
            // Records are emitted after the block closes: GeofenceLogger forwards to the host
            // Logger, whose dispatcher is customer code and must not run under stateLock.
            var userStateChanged = false
            var fixTooOld = false
            var noEvaluableFences = false
            val outcome = synchronized(stateLock) {
                if (store.userStateGeneration() != expectedUserStateGeneration) {
                    userStateChanged = true
                    null
                } else if (!isCurrentSessionFixLocked(fix.elapsedRealtimeNanos)) {
                    fixTooOld = true
                    null
                } else {
                    val fences = activePolygonFencesLocked()
                    if (fences.isEmpty()) {
                        noEvaluableFences = true
                        PolygonRouteOutcome(emptyList(), emptyList())
                    } else {
                        acceptedFix = true
                        val committedStates = store.getEnteredIds()
                            .associateWith { PolygonCommittedState.INSIDE }
                        routeProcessor.process(
                            fences = fences,
                            sample = fix.sample,
                            elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
                            fixAgeSeconds = GeofenceLogTail.fixAgeSeconds(fix.elapsedRealtimeNanos),
                            committedStates = committedStates,
                            answersHeldFixAt = answersHeldFixAt
                        )
                    }
                }
            }
            if (userStateChanged) {
                logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
                return@withLock PolygonEvaluationOutcome.NOTHING
            }
            if (fixTooOld) {
                logger.logPolygonFixNotUsable(
                    PolygonFixRejection.FIX_TOO_OLD,
                    horizontalAccuracyMeters = fix.sample.horizontalAccuracyMeters,
                    fixAgeSeconds = GeofenceLogTail.fixAgeSeconds(fix.elapsedRealtimeNanos)
                )
            }
            if (noEvaluableFences) {
                logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.NO_EVALUABLE_FENCES)
            }
            val routeOutcome = outcome ?: continue
            // Emitted outside stateLock; PolygonLockFreedomTest enforces this.
            routeOutcome.records.filterIsInstance<PolygonRouteRecord.Undecided>()
                .mapTo(undecidedPolygonIds, PolygonRouteRecord.Undecided::geofenceId)
            routeOutcome.records.filterIsInstance<PolygonRouteRecord.ArrivalPending>()
                .mapTo(pendingArrivalPolygonIds, PolygonRouteRecord.ArrivalPending::geofenceId)
            // Every judged fence produces a record; a skipped fence produces none.
            routeOutcome.records.mapTo(evaluatedPolygonIds, PolygonRouteRecord::geofenceId)
            routeOutcome.records.forEach(::emitRouteRecord)
            routeOutcome.detections.forEach { detection ->
                if (store.userStateGeneration() != expectedUserStateGeneration) {
                    // Not under stateLock, so it can log in place.
                    logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
                    return@withLock PolygonEvaluationOutcome.NOTHING
                }
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
                if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock PolygonEvaluationOutcome.NOTHING
                if (detection.transition == PolygonTransition.EXIT) {
                    synchronized(stateLock) {
                        if (store.userStateGeneration() != expectedUserStateGeneration) {
                            return@withLock PolygonEvaluationOutcome.NOTHING
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
        PolygonEvaluationOutcome(
            acceptedFix = acceptedFix,
            undecidedPolygonIds = undecidedPolygonIds,
            pendingArrivalPolygonIds = pendingArrivalPolygonIds,
            evaluatedPolygonIds = evaluatedPolygonIds
        )
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
        // One catalog read: getCachedRegion decodes the whole catalog per call.
        val regions = store.getCachedRegions().filter { it.id in activeIds && it.isPolygon }
        // Keyed on the transition revision, not just the ring: it also covers the enclosing circle,
        // and a fence rebuilt from a stale revision has its detections dropped as replaced geometry.
        val signature = regions.associate { it.id to it.transitionRevision() }
        if (signature == cachedFenceSignature) return cachedFences
        geometryCache.keys.retainAll(regions.mapTo(mutableSetOf()) { it.id })
        cachedFenceSignature = signature
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
                // A ring that no longer validates must not fall back to its enclosing circle, a
                // coarse trigger much wider than the fence.
                logger.logPolygonRegionNotRanked(region.id, PolygonNotRankedReason.RING_UNBUILDABLE)
                null
            } else {
                PolygonFence(region.id, geometry, region.transitionRevision())
            }
        }
        return cachedFences
    }

    private fun invalidateFenceCacheLocked() {
        cachedFenceSignature = emptyMap()
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
        const val TRIGGER_LOCATION_GRACE_NANOS = 30_000_000_000L
        const val MAXIMUM_FIX_AGE_NANOS = 120_000_000_000L
        const val FUTURE_FIX_TOLERANCE_NANOS = 5_000_000_000L
    }

    private data class CachedGeometry(
        val vertices: List<PolygonCoordinate>,
        val geometry: PolygonGeometry?
    )
}
