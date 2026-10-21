package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceDwellCoordinator
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
 * [undecidedPolygonIds], [pendingArrivalPolygonIds] and [dwellUnprovenPolygonIds] cue a better-fix
 * request; an aborted pass reports none. [evaluatedPolygonIds] is every fence judged: one skipped
 * because the fix is not newer is in no set, so absence from [undecidedPolygonIds] is not a decision.
 * The exception is [echoedFixPolygonIds]: fences skipped because a requested fix (`answersHeldFixAt`)
 * came back as the very fix they last judged, with no newer fix judged since.
 *
 * [dwellUnprovenPolygonIds] are entered polygons whose latest judged fix kept them INSIDE without
 * placing its whole accuracy circle inside the ring, while their unemitted visit had already lasted
 * its dwell threshold, so only a more precise fix can show the stay.
 */
internal data class PolygonEvaluationOutcome(
    val acceptedFix: Boolean,
    val undecidedPolygonIds: Set<String>,
    val pendingArrivalPolygonIds: Set<String> = emptySet(),
    val evaluatedPolygonIds: Set<String> = emptySet(),
    val dwellUnprovenPolygonIds: Set<String> = emptySet(),
    val echoedFixPolygonIds: Set<String> = emptySet()
) {
    internal companion object {
        val NOTHING = PolygonEvaluationOutcome(acceptedFix = false, undecidedPolygonIds = emptySet())
    }
}

/**
 * Decides polygon containment from fixes its callers deliver; it never requests location itself.
 * A crossing no wake observes with a usable fix is missed.
 */
internal class PolygonLocationEngine(
    private val store: GeofenceRegionStore,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor,
    private val clock: Clock,
    private val logger: GeofenceLogger,
    private val dwellCoordinator: GeofenceDwellCoordinator? = null,
    /** Experimental dwell wake. Null skips its cues and the visit reads that feed them. */
    private val dwellWakeOwner: PolygonDwellWakeOwner? = null
) {
    private val routeProcessor = PolygonRouteProcessor()
    private var sessionStartElapsedRealtimeNanos: Long? = null
    private val processingMutex = Mutex()
    private val stateLock = Any()
    private val geometryCache = mutableMapOf<String, CachedGeometry>()

    private var cachedFenceSignature: Map<String, Int> = emptyMap()
    private var cachedFences: List<PolygonFence> = emptyList()

    /**
     * The latest fix, per polygon, that decisively placed the device outside it during the current
     * activation. Only an ENTER soon after one is the crossing itself: without a record, committed
     * state reads OUTSIDE, so a device already inside when the polygon activated also reports ENTER,
     * and after a long gap without fixes the device may have arrived at any point in it.
     * In memory, so process death forgets it and the next ENTER reports no observed entry.
     */
    private val provenOutside = mutableMapOf<String, ProvenOutside>()

    private data class ProvenOutside(
        val regionRevision: Int,
        val userStateGeneration: Long,
        val elapsedRealtimeNanos: Long
    )

    /**
     * Bumped by every [stop]. A pass tied to a session through `expectedStopEpoch` refuses to act once
     * it moves. A stop and a re-activation while that pass waited for the processing mutex or the
     * outbox recovery leave the user generation and the active set unchanged, so neither reveals it.
     */
    private var stopEpoch: Long = 0L

    /** Callers read it under their own lock with their teardown check, so both name one session. */
    fun stopEpoch(): Long = synchronized(stateLock) { stopEpoch }

    /** Null never refuses: ordinary passes are not tied to a session and behave as before. */
    private fun stoppedSinceLocked(expectedStopEpoch: Long?): Boolean =
        expectedStopEpoch != null && expectedStopEpoch != stopEpoch

    private fun stoppedSince(expectedStopEpoch: Long?): Boolean =
        expectedStopEpoch != null && synchronized(stateLock) { stoppedSinceLocked(expectedStopEpoch) }

    fun stop(): Set<String> = synchronized(stateLock) {
        stopEpoch += 1
        routeProcessor.clear().also {
            geometryCache.clear()
            provenOutside.clear()
            invalidateFenceCacheLocked()
            sessionStartElapsedRealtimeNanos = null
        }
    }

    fun activate(polygonId: String): Set<String> = synchronized(stateLock) {
        resetEvidenceLocked(polygonId).also { armSessionLocked(restartSession = true) }
    }

    /** Arms from the approach's first fix: a late background batch can predate the normal grace. */
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

    @VisibleForTesting
    internal fun holdsStateLock(): Boolean = Thread.holdsLock(stateLock)

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
        provenOutside.remove(polygonId)
        invalidateFenceCacheLocked()
        return if (wasPending) setOf(polygonId) else emptySet()
    }

    fun deactivate(polygonId: String): Set<String> =
        synchronized(stateLock) { resetEvidenceLocked(polygonId) }

    /**
     * [expectedStopEpoch], read from [stopEpoch], ties the pass to the session its caller validated:
     * any [stop] since then refuses it at every later check, including after the outbox recovery it
     * awaits. Null for ordinary passes, which behave as before.
     */
    suspend fun processResponsiveLocation(
        location: Location,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        answersHeldFixAt: Long? = null,
        expectedStopEpoch: Long? = null
    ): PolygonEvaluationOutcome = processLocations(
        locations = listOf(location),
        expectedUserStateGeneration = expectedUserStateGeneration,
        answersHeldFixAt = answersHeldFixAt,
        expectedStopEpoch = expectedStopEpoch
    )

    // A refusal after a stop is logged as a session change: the stop ended the session the pass began in.
    private suspend fun processLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long,
        answersHeldFixAt: Long?,
        expectedStopEpoch: Long?
    ): PolygonEvaluationOutcome = processingMutex.withLock {
        if (locations.isEmpty()) return@withLock PolygonEvaluationOutcome.NOTHING
        if (store.userStateGeneration() != expectedUserStateGeneration || stoppedSince(expectedStopEpoch)) {
            logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
            return@withLock PolygonEvaluationOutcome.NOTHING
        }
        // Don't evaluate a newer edge while an older one cannot reach the durable queue.
        if (!transitionProcessor.recoverPendingTransitions()) {
            logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.OUTBOX_BLOCKED)
            return@withLock PolygonEvaluationOutcome.NOTHING
        }
        if (store.userStateGeneration() != expectedUserStateGeneration) {
            logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
            return@withLock PolygonEvaluationOutcome.NOTHING
        }
        val sessionArmed = synchronized(stateLock) {
            // The recovery above awaits, so a stop may have landed during it. Refused before arming,
            // because arming would restart the session that stop ended.
            if (store.userStateGeneration() != expectedUserStateGeneration ||
                stoppedSinceLocked(expectedStopEpoch)
            ) {
                false
            } else {
                armSessionLocked()
                true
            }
        }
        if (!sessionArmed) {
            logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
            return@withLock PolygonEvaluationOutcome.NOTHING
        }
        var acceptedFix = false
        val undecidedPolygonIds = mutableSetOf<String>()
        val pendingArrivalPolygonIds = mutableSetOf<String>()
        val evaluatedPolygonIds = mutableSetOf<String>()
        val dwellUnprovenPolygonIds = mutableSetOf<String>()
        val echoedFixPolygonIds = mutableSetOf<String>()
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
            // Records are emitted after the block: the host's log dispatcher is customer code and
            // must not run under stateLock.
            var userStateChanged = false
            var fixTooOld = false
            var noEvaluableFences = false
            val outcome = synchronized(stateLock) {
                if (store.userStateGeneration() != expectedUserStateGeneration ||
                    stoppedSinceLocked(expectedStopEpoch)
                ) {
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
                        ).also { routed ->
                            fences.filter { it.id in routed.provenOutsideIds }.forEach { fence ->
                                provenOutside[fence.id] = ProvenOutside(
                                    regionRevision = fence.regionRevision,
                                    userStateGeneration = expectedUserStateGeneration,
                                    elapsedRealtimeNanos = fix.elapsedRealtimeNanos
                                )
                            }
                        }
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
            routeOutcome.records.filterIsInstance<PolygonRouteRecord.Undecided>()
                .mapTo(undecidedPolygonIds, PolygonRouteRecord.Undecided::geofenceId)
            routeOutcome.records.filterIsInstance<PolygonRouteRecord.ArrivalPending>()
                .mapTo(pendingArrivalPolygonIds, PolygonRouteRecord.ArrivalPending::geofenceId)
            // Every judged fence produces a record; a skipped fence produces none.
            val evaluatedThisFix = routeOutcome.records.mapTo(mutableSetOf(), PolygonRouteRecord::geofenceId)
            // Dwell evidence needs the fix's whole accuracy circle inside the ring, as on iOS. A point
            // inside by less than its accuracy keeps the committed state but proves nothing.
            val insideThisFix = routeOutcome.records
                .filterIsInstance<PolygonRouteRecord.Unchanged>()
                .filter { it.membership == PolygonCommittedState.INSIDE.name }
            val decisivelyInsideThisFix = insideThisFix
                .filter { record ->
                    record.signedBoundaryDistanceMeters?.let { it > record.horizontalAccuracyMeters } == true
                }
                .mapTo(mutableSetOf(), PolygonRouteRecord::geofenceId)
            // Kept INSIDE but proving nothing: a precise fix may still show the stay (see below).
            val marginallyInsideThisFix =
                insideThisFix.mapTo(mutableSetOf(), PolygonRouteRecord::geofenceId) - decisivelyInsideThisFix
            evaluatedPolygonIds.addAll(evaluatedThisFix)
            echoedFixPolygonIds.addAll(routeOutcome.echoedFixIds)
            routeOutcome.records.forEach(::emitRouteRecord)
            // Only a visit this fix starts, emits or ends cues the wake owner; an ordinary fix queues nothing.
            val dwellWakeCues = DwellWakeCues()
            routeOutcome.detections.forEach { detection ->
                val visitBefore = visitMarkerOrNull(detection.polygonId)
                if (store.userStateGeneration() != expectedUserStateGeneration ||
                    stoppedSince(expectedStopEpoch)
                ) {
                    logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
                    return@withLock PolygonEvaluationOutcome.NOTHING
                }
                val transition = when (detection.transition) {
                    PolygonTransition.ENTER -> Event.GeofenceTransition.ENTER
                    PolygonTransition.EXIT -> Event.GeofenceTransition.EXIT
                }
                val observedAtSeconds = observedTimestampSeconds(fix)
                val wasInside = detection.transition == PolygonTransition.ENTER &&
                    detection.polygonId in store.getEnteredIds()
                val polygonExit = if (detection.transition == PolygonTransition.EXIT) {
                    dwellCoordinator?.capturePolygonExit(
                        geofenceId = detection.polygonId,
                        exitedAtSeconds = observedAtSeconds,
                        exitFixElapsedMs = fix.elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
                        expectedUserStateGeneration = expectedUserStateGeneration
                    )
                } else {
                    null
                }
                val outsideObserved = detection.transition == PolygonTransition.ENTER &&
                    synchronized(stateLock) {
                        provenOutside[detection.polygonId]?.let {
                            it.regionRevision == detection.regionRevision &&
                                it.userStateGeneration == expectedUserStateGeneration &&
                                it.elapsedRealtimeNanos < fix.elapsedRealtimeNanos &&
                                fix.elapsedRealtimeNanos - it.elapsedRealtimeNanos <= MAX_OUTSIDE_PROOF_AGE_NANOS
                        } == true
                    }
                transitionProcessor.process(
                    geofenceId = detection.polygonId,
                    transition = transition,
                    timestampSeconds = observedAtSeconds,
                    enforceConfiguredTransition = true,
                    expectedRegionRevision = detection.regionRevision,
                    expectedUserStateGeneration = expectedUserStateGeneration,
                    requireRegistered = true,
                    visitContext = polygonExit?.visitContext,
                    // The coordinator ends the visit this EXIT read (see onPolygonExit below). Ending
                    // by wall time could clear a newer visit, or keep this one after a clock step.
                    endsVisitByTimestamp = false
                )
                if (store.userStateGeneration() != expectedUserStateGeneration) {
                    return@withLock PolygonEvaluationOutcome.NOTHING
                }
                if (
                    detection.transition == PolygonTransition.ENTER &&
                    detection.polygonId in store.getEnteredIds()
                ) {
                    dwellCoordinator?.onEnter(
                        detection.polygonId,
                        observedAtSeconds,
                        expectedUserStateGeneration,
                        beginsNewVisit = !wasInside,
                        polygonOutsideObserved = outsideObserved,
                        enteredAtElapsedMs = fix.elapsedRealtimeNanos / NANOS_PER_MILLISECOND
                    )
                }
                if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock PolygonEvaluationOutcome.NOTHING
                if (detection.transition == PolygonTransition.EXIT && detection.polygonId !in store.getEnteredIds()) {
                    dwellCoordinator?.onPolygonExit(
                        detection.polygonId,
                        exitFixElapsedMs = fix.elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
                        endedVisitId = polygonExit?.endedVisitId,
                        expectedUserStateGeneration = expectedUserStateGeneration
                    )
                }
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
                dwellWakeCues.compare(detection.polygonId, visitBefore)
            }
            val observedAtSeconds = observedTimestampSeconds(fix)
            val enteredIds = store.getEnteredIds()
            decisivelyInsideThisFix.filter { it in enteredIds }.forEach { geofenceId ->
                // Re-checked per fence: the detection commits above await, and this evidence may qualify a dwell.
                if (stoppedSince(expectedStopEpoch)) {
                    logger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED)
                    return@withLock PolygonEvaluationOutcome.NOTHING
                }
                val visitBefore = visitMarkerOrNull(geofenceId)
                dwellCoordinator?.onInsideEvidence(
                    geofenceId,
                    observedAtSeconds,
                    expectedUserStateGeneration,
                    observedAtElapsedMs = fix.elapsedRealtimeNanos / NANOS_PER_MILLISECOND
                )
                dwellWakeCues.compare(geofenceId, visitBefore)
            }
            // The latest fix to judge a fence decides whether it still awaits proof, so a later fix in
            // the batch that decided it, or found it no longer inside, withdraws an earlier cue.
            dwellUnprovenPolygonIds.removeAll(evaluatedThisFix)
            marginallyInsideThisFix.filter { it in enteredIds }.forEach { geofenceId ->
                val awaitsProof = dwellCoordinator?.awaitsDwellProof(
                    geofenceId,
                    atElapsedMs = fix.elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
                    expectedUserStateGeneration = expectedUserStateGeneration
                ) == true
                if (awaitsProof) dwellUnprovenPolygonIds += geofenceId
            }
            // Launched, never joined: this pass holds the processing mutex. An aborted pass cues nothing;
            // a moved generation fails the owner's own checks, and its teardown restores what was armed.
            dwellWakeCues.dispatch()
        }
        PolygonEvaluationOutcome(
            acceptedFix = acceptedFix,
            undecidedPolygonIds = undecidedPolygonIds,
            pendingArrivalPolygonIds = pendingArrivalPolygonIds,
            evaluatedPolygonIds = evaluatedPolygonIds,
            dwellUnprovenPolygonIds = dwellUnprovenPolygonIds,
            // A fence that another fix in the batch judged has a verdict of its own.
            echoedFixPolygonIds = echoedFixPolygonIds - evaluatedPolygonIds
        )
    }

    /**
     * A visit as the wake owner's cues compare it. [awaitsDwell] reads as the owner's pending check
     * does: a reserved dwell no longer awaits, whether or not its emit mark has landed.
     */
    private data class VisitMarker(val visitId: String, val awaitsDwell: Boolean)

    /** What one fix changed for [dwellWakeOwner], so an unchanged visit queues nothing. */
    private class DwellWakeCues {
        val pending = mutableSetOf<String>()
        val settled = mutableSetOf<String>()
    }

    private fun visitMarkerOrNull(polygonId: String): VisitMarker? {
        if (dwellWakeOwner == null) return null
        val visit = store.getDwellVisit(polygonId) ?: return null
        return VisitMarker(visit.visitId, awaitsDwell = !visit.emitted && visit.dwellReservation == null)
    }

    /**
     * A visit that appeared awaiting its dwell is pending. One that existed before and then ended,
     * stopped awaiting (its first reservation or emit mark), or was replaced is settled; a replacement
     * is both, so the old wake is undone and the new visit may arm. The owner re-checks both against
     * the store under its gate.
     */
    private fun DwellWakeCues.compare(polygonId: String, before: VisitMarker?) {
        if (dwellWakeOwner == null) return
        val after = visitMarkerOrNull(polygonId)
        if (after != null && after.awaitsDwell && after.visitId != before?.visitId) pending += polygonId
        val stoppedAwaiting = after?.awaitsDwell == false && before?.awaitsDwell == true
        if (before != null && (after?.visitId != before.visitId || stoppedAwaiting)) {
            settled += polygonId
        }
    }

    private fun DwellWakeCues.dispatch() {
        val owner = dwellWakeOwner ?: return
        settled.forEach { owner.onVisitSettled(it) }
        pending.forEach { owner.onVisitPending(it) }
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
        // Keyed on transition revision, not the active id set: a sync can edit a fence without
        // changing the set, and a fence built from a stale revision has its detections dropped.
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
        const val MAX_OUTSIDE_PROOF_AGE_NANOS = GeofenceConstants.MAX_OUTSIDE_PROOF_AGE_MS * NANOS_PER_MILLISECOND
        const val FUTURE_FIX_TOLERANCE_NANOS = 5_000_000_000L
    }

    private data class CachedGeometry(
        val vertices: List<PolygonCoordinate>,
        val geometry: PolygonGeometry?
    )
}
