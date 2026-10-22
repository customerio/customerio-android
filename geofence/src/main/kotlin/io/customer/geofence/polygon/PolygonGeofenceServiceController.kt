package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import io.customer.geofence.DwellDiagnosticEvent
import io.customer.geofence.DwellDiagnosticReason
import io.customer.geofence.DwellDiagnosticRecord
import io.customer.geofence.DwellDiagnosticRecorder
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceRegistrar
import io.customer.geofence.GeofenceTransitionType
import io.customer.geofence.PolygonArrivalExpiry
import io.customer.geofence.PolygonCallbackDrop
import io.customer.geofence.PolygonFreshFixSkip
import io.customer.geofence.PolygonSamplingSkip
import io.customer.geofence.distanceTo
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.dwellProofBudgetAllows
import io.customer.geofence.store.getCachedConfigOrFallback
import io.customer.geofence.store.registrationsPredateBoot
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists coarse-circle activity and coordinates responsive polygon evaluation. */
internal class PolygonGeofenceServiceController(
    private val context: Context,
    private val store: GeofenceRegionStore,
    private val engine: PolygonLocationEngine,
    private val approachMonitor: PolygonApproachMonitor,
    private val manager: GeofenceRegistrar,
    private val secureUserStore: SecureUserStore,
    private val freshFixSource: PolygonFreshFixSource,
    private val recheckScheduler: PolygonRecheckScheduler,
    private val passiveMonitor: PolygonPassiveMonitor,
    private val bootSessionProvider: PolygonBootSessionProvider,
    private val logger: GeofenceLogger,
    /** Background checks at pending polygon visits' thresholds. Called only outside [controllerLock]. */
    private val dwellCheckScheduler: PolygonDwellCheckScheduler? = null,
    /** Opt-in dwell diagnostics. Invalidation only queues its wipe, so it may run under [controllerLock]. */
    private val diagnosticRecorder: DwellDiagnosticRecorder? = null
) {
    private val movementTriggerPolicy = PolygonMovementTriggerPolicy()

    /** Never log while holding this: [GeofenceLogger] forwards to customer code. */
    private val controllerLock = Any()

    private var lastFreshFixRequestElapsedMs: Long? = null
    private var lastFreshFix: Location? = null

    /**
     * Bumped by every teardown, so a wake that captured it cannot re-arm what the teardown removed.
     * Needed because teardown keeps the routable ids and the user generation.
     */
    private var teardownGeneration: Long = 0L

    fun teardownGeneration(): Long = synchronized(controllerLock) { teardownGeneration }

    private fun isAfterTeardown(expected: Long?): Boolean = expected != null &&
        synchronized(controllerLock) { isAfterTeardownLocked(expected) }

    /** The authoritative check; unlocked [isAfterTeardown] calls are only a fast path. */
    private fun isAfterTeardownLocked(expected: Long?): Boolean =
        expected != null && expected != teardownGeneration

    /**
     * Called by every teardown: the fix memos are not keyed by user, so a previous session's fix or
     * rate limit would otherwise leak into the next user's callbacks.
     */
    private fun forgetRequestedFix() {
        lastFreshFix = null
        lastFreshFixRequestElapsedMs = null
        teardownGeneration += 1
        futileEscalations.clear()
    }

    /** A requested precise fix that still could not decide its polygon. */
    private data class FutileEscalation(
        val fix: Location,
        val atElapsedMs: Long
    )

    private val futileEscalations = mutableMapOf<String, FutileEscalation>()
    private val coarseTransitionMutex = Mutex()
    private val lastCoarseTransitionElapsedNanos = mutableMapOf<String, Long>()

    /**
     * Also runs for ENTERs GMS re-reports after an identify: the identify cleared containment, and
     * polygon membership belongs to the location evaluator rather than registration's point check.
     */
    suspend fun activate(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        expectedRegionRevision: Int? = null,
        expectedTeardownGeneration: Long? = null,
        /** Taken at the call, before the mutex wait, so a reset meanwhile retires this wake's record. */
        diagnosticToken: Long? = diagnosticRecorder?.currentToken()
    ) = coarseTransitionMutex.withLock {
        recordDiagnostic(
            expectedUserStateGeneration,
            DwellDiagnosticEvent.WAKE_TRANSITION,
            polygonId,
            triggeringLocation,
            diagnosticToken = diagnosticToken
        )
        if (isAfterTeardown(expectedTeardownGeneration)) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, polygonId)
            return@withLock
        }
        if (!isCurrentRegisteredPolygon(polygonId, expectedUserStateGeneration, expectedRegionRevision)) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, polygonId)
            return@withLock
        }
        if (!acceptCoarseTransition(polygonId, triggeringLocation)) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.DUPLICATE_DELIVERY, polygonId)
            return@withLock
        }
        val armed = activate(
            polygonId,
            expectedUserStateGeneration,
            expectedRegionRevision,
            expectedTeardownGeneration
        )
        if (!armed) return@withLock
        evaluateCallbackFix(polygonId, triggeringLocation, expectedUserStateGeneration)
    }

    /**
     * A scheduled check of [visitId], the polygon's visit when it was planned. It may only look for the
     * strict proof the engine already requires, with one precise fix charged to the visit's budget. It
     * takes no coarse state or ordering slot, so later ENTERs and EXITs are judged as before.
     *
     * @return how long to wait before checking again without counting this check, when another
     *   request's cooldown refused it; null when the check ran, or the visit needs no more.
     */
    suspend fun onDwellCheck(
        polygonId: String,
        visitId: String,
        expectedUserStateGeneration: Long,
        expectedTeardownGeneration: Long
    ): Long? {
        recordDiagnostic(expectedUserStateGeneration, DwellDiagnosticEvent.WAKE_DWELL_CHECK, polygonId, null)
        return coarseTransitionMutex.withLock {
            val visit = store.getDwellVisit(polygonId)
            val stillPending = visit != null && visit.visitId == visitId && !visit.emitted &&
                visit.dwellReservation == null
            if (!stillPending || isAfterTeardown(expectedTeardownGeneration) ||
                !activateForDwellCheck(polygonId, expectedUserStateGeneration, expectedTeardownGeneration)
            ) {
                logger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, polygonId)
                return@withLock null
            }
            val skipped = requestDwellProof(polygonId, expectedUserStateGeneration, expectedTeardownGeneration)
            // Another visit's request started the shared cooldown: not this visit's attempt.
            FRESH_FIX_COOLDOWN_MS.takeIf { skipped == DwellDiagnosticReason.WITHIN_COOLDOWN }
        }
    }

    /**
     * One precise fix for a dwell check, charged to the visit's budget before it is requested. The
     * teardown token is re-checked after every await: teardown keeps the visit and a later callback can
     * re-activate the engine, so a late answer would otherwise still qualify it.
     *
     * @return why no fix was obtained, or null when one was.
     */
    private suspend fun requestDwellProof(
        polygonId: String,
        expectedUserStateGeneration: Long,
        expectedTeardownGeneration: Long
    ): DwellDiagnosticReason? {
        // Taken before any await, so a reset while a request is out retires what its answer records.
        val diagnosticToken = diagnosticRecorder?.currentToken()
        fun recordDwellRejected(reason: DwellDiagnosticReason) = recordDiagnostic(
            expectedUserStateGeneration,
            DwellDiagnosticEvent.DWELL_REJECTED,
            fenceId = polygonId,
            reason = reason,
            diagnosticToken = diagnosticToken
        )
        val budget = withinDwellProofBudget(setOf(polygonId))
        if (budget.isEmpty()) {
            recordDwellRejected(DwellDiagnosticReason.BUDGET_EXHAUSTED)
            return DwellDiagnosticReason.BUDGET_EXHAUSTED
        }
        var skipped: DwellDiagnosticReason? = null
        val fresh = preciseFixForCallback(
            polygonId,
            undecidedPolygonIds = setOf(polygonId),
            pendingArrivalPolygonIds = emptySet(),
            triggeringLocation = null,
            expectedTeardownGeneration = expectedTeardownGeneration,
            onSkipped = { reason ->
                skipped = reason
                recordDwellRejected(reason)
            },
            onRequesting = { at -> chargeDwellProofRequests(budget, at) }
        ) ?: return skipped ?: DwellDiagnosticReason.NOT_CURRENT_SESSION
        if (isAfterTeardown(expectedTeardownGeneration)) return DwellDiagnosticReason.NOT_CURRENT_SESSION
        evaluateAndRecentre(
            fresh.fix,
            expectedUserStateGeneration,
            expectedTeardownGeneration = expectedTeardownGeneration
        )
        return null
    }

    /**
     * Has the engine evaluate the polygon, as [onMovementTriggerExit] does for an entered one. No
     * coarse-inside write: a DWELL is no coarse edge.
     */
    private fun activateForDwellCheck(
        polygonId: String,
        expectedUserStateGeneration: Long,
        expectedTeardownGeneration: Long
    ): Boolean {
        val discarded = mutableSetOf<String>()
        val routable = synchronized(controllerLock) {
            if (isAfterTeardownLocked(expectedTeardownGeneration) ||
                !isCurrentRegisteredPolygonLocked(polygonId, expectedUserStateGeneration, expectedRegionRevision = null)
            ) {
                return@synchronized false
            }
            if (polygonId !in store.getActivePolygonIds()) {
                store.activatePolygon(polygonId)
                discarded += engine.activate(polygonId)
                approachMonitor.start(expectedUserStateGeneration)
            }
            true
        }
        reportDiscardedArrivals(discarded)
        return routable
    }

    /** Returns whether the polygon was armed. */
    fun activate(
        polygonId: String,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        expectedRegionRevision: Int? = null,
        expectedTeardownGeneration: Long? = null
    ): Boolean {
        val discarded = mutableSetOf<String>()
        val routable = synchronized(controllerLock) {
            if (isAfterTeardownLocked(expectedTeardownGeneration)) {
                return@synchronized false
            }
            if (!isCurrentRegisteredPolygonLocked(
                    polygonId,
                    expectedUserStateGeneration,
                    expectedRegionRevision
                )
            ) {
                return@synchronized false
            }
            val alreadyActive = polygonId in store.getActivePolygonIds()
            store.recordPolygonCoarseInside(polygonId)
            store.activatePolygon(polygonId)
            if (!alreadyActive) discarded += engine.activate(polygonId)
            approachMonitor.start(expectedUserStateGeneration)
            true
        }
        reportDiscardedArrivals(discarded)
        if (!routable) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, polygonId)
        }
        return routable
    }

    /** Returns the discarded arrival holds, for the caller to report after leaving the lock. */
    private fun deactivateLocked(
        polygonId: String,
        expectedUserStateGeneration: Long
    ): Set<String> {
        store.recordPolygonCoarseOutside(polygonId)
        store.deactivatePolygon(polygonId)
        val holds = engine.deactivate(polygonId).toMutableSet()
        if (store.getActivePolygonIds().isEmpty()) {
            holds += engine.stop()
            approachMonitor.stop(expectedUserStateGeneration)
        }
        return holds
    }

    /**
     * Takes a teardown token because its tail re-activates a polygon still in the entered set,
     * which teardown retains; without it a late departure would restart approach sampling.
     */
    suspend fun onCoarseExit(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        expectedRegionRevision: Int? = null,
        expectedTeardownGeneration: Long? = null,
        /** Taken at the call, before the mutex wait, so a reset meanwhile retires this wake's record. */
        diagnosticToken: Long? = diagnosticRecorder?.currentToken()
    ) = coarseTransitionMutex.withLock {
        recordDiagnostic(
            expectedUserStateGeneration,
            DwellDiagnosticEvent.WAKE_TRANSITION,
            polygonId,
            triggeringLocation,
            diagnosticToken = diagnosticToken
        )
        if (isAfterTeardown(expectedTeardownGeneration)) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, polygonId)
            return@withLock
        }
        if (!isCurrentRegisteredPolygon(polygonId, expectedUserStateGeneration, expectedRegionRevision)) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, polygonId)
            return@withLock
        }
        if (!acceptCoarseTransition(polygonId, triggeringLocation)) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.DUPLICATE_DELIVERY, polygonId)
            return@withLock
        }
        val recordedCoarseExit = synchronized(controllerLock) {
            // Guards the write and fix evaluation below; the tail check guards only the re-arm.
            if (isAfterTeardownLocked(expectedTeardownGeneration)) {
                false
            } else if (!isCurrentRegisteredPolygonLocked(
                    polygonId,
                    expectedUserStateGeneration,
                    expectedRegionRevision
                )
            ) {
                false
            } else {
                store.recordPolygonCoarseOutside(polygonId)
                true
            }
        }
        if (!recordedCoarseExit) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, polygonId)
            return@withLock
        }
        evaluateCallbackFix(polygonId, triggeringLocation, expectedUserStateGeneration)
        val discarded = synchronized(controllerLock) {
            // Re-checked: evaluateCallbackFix suspends, so a teardown may have landed since.
            if (isAfterTeardownLocked(expectedTeardownGeneration)) return@synchronized emptySet()
            if (!isCurrentRegisteredPolygonLocked(
                    polygonId,
                    expectedUserStateGeneration,
                    expectedRegionRevision
                )
            ) {
                return@synchronized emptySet()
            }
            // A catalog refresh may have activated from a newer fix meanwhile; this older EXIT
            // must not tear that session down.
            if (polygonId in store.getCoarseInsidePolygonIds()) return@synchronized emptySet()
            if (polygonId in store.getEnteredIds()) {
                store.activatePolygon(polygonId)
                approachMonitor.start(expectedUserStateGeneration)
                emptySet()
            } else {
                deactivateLocked(polygonId, expectedUserStateGeneration)
            }
        }
        reportDiscardedArrivals(discarded)
    }

    fun resetEvidence(polygonId: String) {
        val discarded = synchronized(controllerLock) { engine.resetEvidence(polygonId) }
        reportDiscardedArrivals(discarded)
    }

    suspend fun onMovementTriggerExit(
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ): Float? {
        recordDiagnostic(
            expectedUserStateGeneration,
            DwellDiagnosticEvent.WAKE_TRANSITION,
            GeofenceConstants.MOVEMENT_TRIGGER_ID,
            triggeringLocation
        )
        if (triggeringLocation == null) return null
        val fix = triggeringLocation.toPolygonLocationFix()
        var notCurrentSession = false
        val discarded = mutableSetOf<String>()
        val activated = synchronized(controllerLock) {
            if (!hasMatchingIdentifiedUserLocked() ||
                store.userStateGeneration() != expectedUserStateGeneration
            ) {
                notCurrentSession = true
                return@synchronized false
            }
            val activeIds = store.getActivePolygonIds()
            val enteredIds = store.getEnteredIds()
            val polygonIds = store.getRoutableRegisteredIds().filterTo(mutableSetOf()) { id ->
                val region = store.getCachedRegion(id)
                region?.isPolygon == true &&
                    (
                        id in activeIds ||
                            id in enteredIds ||
                            fix != null &&
                            region.distanceTo(
                                triggeringLocation.latitude,
                                triggeringLocation.longitude
                            ) - fix.sample.horizontalAccuracyMeters <= region.radius
                        )
            }
            polygonIds.forEach { polygonId ->
                if (polygonId !in store.getActivePolygonIds()) {
                    store.activatePolygon(polygonId)
                    discarded += engine.activate(polygonId)
                }
            }
            if (polygonIds.isNotEmpty()) approachMonitor.start(expectedUserStateGeneration)
            polygonIds.isNotEmpty()
        }
        reportDiscardedArrivals(discarded)
        if (!activated) {
            // Kept apart: a session mismatch can lose an arrival, so it must not read as routine.
            logger.logPolygonCallbackDropped(
                if (notCurrentSession) {
                    PolygonCallbackDrop.NOT_CURRENT_SESSION
                } else {
                    PolygonCallbackDrop.NO_POLYGON_IN_RANGE
                }
            )
            return null
        }
        val accepted =
            processTriggeredLocation(triggeringLocation, expectedUserStateGeneration).acceptedFix
        return if (accepted) {
            updateMovementTriggerFromAcceptedFix(
                triggeringLocation,
                expectedUserStateGeneration
            )
        } else {
            null
        }
    }

    fun reconcileRegisteredPolygons(ids: Set<String>) {
        val discarded = synchronized(controllerLock) {
            val removed = store.getActivePolygonIds() - ids
            store.retainActivePolygonIds(ids)
            store.retainCoarseInsidePolygonIds(ids)
            val holds = removed.flatMapTo(mutableSetOf(), engine::deactivate)
            lastCoarseTransitionElapsedNanos.keys.retainAll(ids)
            if (store.getActivePolygonIds().isEmpty() || !hasMatchingIdentifiedUserLocked()) {
                approachMonitor.stop(store.userStateGeneration())
            }
            if (store.getActivePolygonIds().isEmpty()) holds += engine.stop()
            holds
        }
        reportDiscardedArrivals(discarded)
        // Keyed on registered ids, not active ones: the re-check is for a polygon that never woke.
        if (ids.isEmpty()) {
            stopScheduledWakes()
        } else {
            // Passive fires only when another request computes a fix, so it complements the re-check.
            recheckScheduler.schedule()
            passiveMonitor.start()
        }
    }

    fun recover() {
        var enteredPolygonIds: Set<String> = emptySet()
        val discarded = synchronized(controllerLock) {
            when {
                !hasMatchingIdentifiedUserLocked() -> invalidatePersistedCoarseStateLocked()
                osStateWasWiped() -> invalidatePersistedCoarseStateLocked()
                else -> {
                    if (store.getActivePolygonIds().isEmpty()) {
                        approachMonitor.stop(store.userStateGeneration())
                    }
                    enteredPolygonIds = store.getEnteredIds().filterTo(mutableSetOf()) {
                        store.getCachedRegion(it)?.isPolygon == true
                    }
                    emptySet()
                }
            }
        }
        reportDiscardedArrivals(discarded)
        // Once per recovery, not per fix: a visit an earlier process left pending gets its check.
        if (enteredPolygonIds.isNotEmpty()) dwellCheckScheduler?.reconcile()
    }

    suspend fun processApproachLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long,
        sessionDeadlineElapsedRealtimeMs: Long
    ): PolygonSamplingDecision {
        // One record per batch, carrying the newest fix; an empty batch is still a wake.
        recordDiagnostic(
            expectedUserStateGeneration,
            DwellDiagnosticEvent.WAKE_APPROACH_FIX,
            fenceId = null,
            fix = locations.maxByOrNull(Location::getElapsedRealtimeNanos)
        )
        if (locations.isEmpty()) {
            logger.logPolygonSamplingSkipped(PolygonSamplingSkip.EMPTY_BATCH)
            return PolygonSamplingDecision.CONTINUE
        }
        val admitted = synchronized(controllerLock) {
            hasMatchingIdentifiedUserLocked() &&
                store.userStateGeneration() == expectedUserStateGeneration
        }
        if (!admitted) {
            logger.logPolygonSamplingSkipped(PolygonSamplingSkip.NOT_CURRENT_SESSION)
            return PolygonSamplingDecision.STALE
        }
        approachMonitor.recordSampleDelivered(sessionDeadlineElapsedRealtimeMs)
        var lastAcceptedLocation: Location? = null
        var lastDelivered: PolygonEvaluationOutcome? = null
        for (location in locations.sortedBy(Location::getElapsedRealtimeNanos)) {
            val fix = location.toPolygonLocationFix()
            if (fix == null) {
                logger.logPolygonSamplingSkipped(PolygonSamplingSkip.NO_USABLE_FIX)
                continue
            }
            var staleBeforeProcessing = false
            val discardedByActivation = mutableSetOf<String>()
            val shouldProcess = synchronized(controllerLock) {
                if (!hasMatchingIdentifiedUserLocked() ||
                    store.userStateGeneration() != expectedUserStateGeneration
                ) {
                    staleBeforeProcessing = true
                    return@synchronized false
                }
                val routableIds = store.getRoutableRegisteredIds()
                val polygons = store.getCachedRegions().filter {
                    it.id in routableIds && it.isPolygon
                }
                // No accuracy margin: the circle is only tens of metres wider than the ring, so a
                // coarse fix would never qualify, and a missed admission loses the visit.
                polygons.filter {
                    it.distanceTo(location.latitude, location.longitude) <= it.radius
                }
                    .forEach { region ->
                        if (region.id !in store.getActivePolygonIds()) {
                            store.activatePolygon(region.id)
                            discardedByActivation +=
                                engine.activateFromApproach(region.id, fix.elapsedRealtimeNanos)
                        }
                    }
                store.getActivePolygonIds().isNotEmpty()
            }
            reportDiscardedArrivals(discardedByActivation)
            if (staleBeforeProcessing) {
                logger.logPolygonSamplingSkipped(PolygonSamplingSkip.NOT_CURRENT_SESSION)
                return PolygonSamplingDecision.STALE
            }
            if (!shouldProcess) {
                logger.logPolygonSamplingSkipped(PolygonSamplingSkip.NO_POLYGON_IN_RANGE)
                continue
            }
            val delivered = processTriggeredLocation(location, expectedUserStateGeneration)
            if (delivered.acceptedFix) {
                lastAcceptedLocation = location
                lastDelivered = delivered
            }
            var staleAfterProcessing = false
            val discardedByDeactivation = mutableSetOf<String>()
            synchronized(controllerLock) {
                if (!hasMatchingIdentifiedUserLocked() ||
                    store.userStateGeneration() != expectedUserStateGeneration
                ) {
                    staleAfterProcessing = true
                    return@synchronized
                }
                val coarseInside = store.getCoarseInsidePolygonIds()
                val committedInside = store.getEnteredIds()
                val approachOnly = store.getActivePolygonIds() - coarseInside - committedInside
                approachOnly.forEach { id ->
                    val region = store.getCachedRegion(id)
                    val confidentlyOutside = region?.isPolygon != true ||
                        region.distanceTo(location.latitude, location.longitude) -
                        fix.sample.horizontalAccuracyMeters >
                        region.radius + APPROACH_EXIT_HYSTERESIS_METERS
                    if (confidentlyOutside) {
                        store.deactivatePolygon(id)
                        discardedByDeactivation += engine.deactivate(id)
                    }
                }
            }
            reportDiscardedArrivals(discardedByDeactivation)
            if (staleAfterProcessing) {
                logger.logPolygonSamplingSkipped(PolygonSamplingSkip.NOT_CURRENT_SESSION)
                return PolygonSamplingDecision.STALE
            }
        }
        // After the whole batch, never between its samples: a precise fix taken now is newer than
        // every sample, and judging it before an older sample could let that sample reopen a visit
        // the fix had just ended. The latest accepted sample's verdict says what still lacks proof.
        val sampleNeedingProof = lastAcceptedLocation
        val delivered = lastDelivered
        if (sampleNeedingProof != null && delivered != null) {
            lastAcceptedLocation = requestDwellProofForSample(sampleNeedingProof, delivered, expectedUserStateGeneration)
                ?: sampleNeedingProof
        }
        val discardedBySessionEnd = mutableSetOf<String>()
        val sessionIsCurrent = synchronized(controllerLock) {
            if (!hasMatchingIdentifiedUserLocked() ||
                store.userStateGeneration() != expectedUserStateGeneration
            ) {
                return@synchronized false
            }
            if (store.getActivePolygonIds().isEmpty()) discardedBySessionEnd += engine.stop()
            true
        }
        reportDiscardedArrivals(discardedBySessionEnd)
        if (!sessionIsCurrent) return PolygonSamplingDecision.STALE
        val safelyPassive = lastAcceptedLocation?.let {
            updateMovementTriggerFromAcceptedFix(it, expectedUserStateGeneration)
        } != null
        // Re-checked: the trigger update awaits GMS, and a CONTINUE for an ended generation would
        // replace the approach request a new user armed in that gap.
        return synchronized(controllerLock) {
            if (!hasMatchingIdentifiedUserLocked() ||
                store.userStateGeneration() != expectedUserStateGeneration
            ) {
                PolygonSamplingDecision.STALE
            } else if (safelyPassive || store.getActivePolygonIds().isEmpty()) {
                PolygonSamplingDecision.STOP
            } else {
                PolygonSamplingDecision.CONTINUE
            }
        }
    }

    private companion object {
        const val APPROACH_EXIT_HYSTERESIS_METERS = 100.0

        /**
         * Spent from `GeofenceBroadcastReceiver`'s 8 s `DISPATCH_WAIT_BUDGET_MS` ahead of the
         * movement-refresh join, so kept short. A timeout keeps the cooldown armed, so a dispatch
         * waits at most once.
         */
        const val FRESH_FIX_TIMEOUT_MS = 3_000L

        const val FRESH_FIX_COOLDOWN_MS = 30_000L

        /** How long a futile escalation suppresses a repeat for the same polygon and position. */
        const val FUTILE_ESCALATION_RETRY_MS = 30 * 60_000L

        /**
         * How long an obtained fix may answer later callbacks from the same GMS batch. Kept short:
         * a reused fix bypasses the cooldown, so a longer window could decide from a position left.
         */
        const val BATCH_REUSE_WINDOW_MS = 2_000L

        const val MILLIS_PER_SECOND = 1_000.0
        const val NANOS_PER_MILLI = 1_000_000L
    }

    /** A fix for a callback, with the token of the request that produced it. */
    private data class CallbackFix(val fix: Location, val requestSessionElapsedMs: Long?)

    /** What the cooldown decided, resolved under the lock so two callbacks cannot both request. */
    private sealed interface CallbackFixDecision {
        /**
         * Carries the producing request's token, read under this lock, so recording from a reused
         * fix is still checked against a teardown.
         */
        data class Reuse(val fix: Location, val requestSessionElapsedMs: Long?) : CallbackFixDecision
        data object Request : CallbackFixDecision
        data object WithinCooldown : CallbackFixDecision
        data object UnchangedPosition : CallbackFixDecision
    }

    /**
     * Outside [controllerLock]: WorkManager does disk work. Teardowns call this before their wipe,
     * so [PolygonPassiveMonitor.isArmed] refuses later deliveries and one admitted earlier is wiped.
     * Unconditional: some teardowns keep registrations, so keying on them would re-arm the wake.
     */
    private fun stopScheduledWakes() {
        recheckScheduler.cancel()
        passiveMonitor.stop()
    }

    fun invalidatePersistedCoarseState() {
        val discarded = synchronized(controllerLock) { invalidatePersistedCoarseStateLocked() }
        reportDiscardedArrivals(discarded)
    }

    private fun invalidatePersistedCoarseStateLocked(): Set<String> {
        val generation = store.userStateGeneration()
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        val discarded = engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        forgetRequestedFix()
        approachMonitor.stop(generation)
        return discarded
    }

    fun invalidateOsRegistrationState() {
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            val generation = store.userStateGeneration()
            store.saveRegisteredIds(emptySet())
            store.saveRoutableRegisteredIds(emptySet())
            store.saveRetainedRegisteredRegions(emptyList())
            // GMS stopped monitoring without reporting what happened meanwhile, so no visit can
            // span the gap. Queued deliveries are outbound facts and stay as they are.
            store.invalidateDwellContinuity()
            store.clearActivePolygonIds()
            store.retainCoarseInsidePolygonIds(emptySet())
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(generation)
            holds
        }
        dwellCheckScheduler?.reconcile()
        reportDiscardedArrivals(discarded)
    }

    fun stopAll() {
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            val generation = store.userStateGeneration()
            store.clearActivePolygonIds()
            store.retainCoarseInsidePolygonIds(emptySet())
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(generation)
            holds
        }
        // After the wipe and outside the lock, so it plans from what the teardown left.
        dwellCheckScheduler?.reconcile()
        reportDiscardedArrivals(discarded)
    }

    fun clearUserScopedState() {
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            // Wiped under the lock coarse callbacks check under, so a queued callback cannot re-arm.
            val generation = store.userStateGeneration()
            store.clearUserScopedState()
            retireDiagnosticsIfSessionEndedLocked(generation)
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(generation)
            holds
        }
        dwellCheckScheduler?.reconcile()
        reportDiscardedArrivals(discarded)
    }

    fun clearUserSessionRetainingOsRegistrations() {
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            val generation = store.userStateGeneration()
            store.clearUserSessionRetainingOsRegistrations()
            retireDiagnosticsIfSessionEndedLocked(generation)
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(generation)
            holds
        }
        dwellCheckScheduler?.reconcile()
        reportDiscardedArrivals(discarded)
    }

    fun completeUserReset(
        expectedUserStateGeneration: Long,
        osRegistrationsCleared: Boolean
    ) {
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            val previousGeneration = store.userStateGeneration()
            store.completeUserReset(expectedUserStateGeneration, osRegistrationsCleared)
            // A superseded reset found a newer session in force; that user's records stay.
            if (previousGeneration == expectedUserStateGeneration) {
                retireDiagnosticsIfSessionEndedLocked(previousGeneration)
            }
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(expectedUserStateGeneration)
            holds
        }
        dwellCheckScheduler?.reconcile()
        reportDiscardedArrivals(discarded)
    }

    fun beginUserSession(userId: String) {
        val opened = synchronized(controllerLock) {
            openSessionLocked { store.beginUserSession(userId) }
        }
        onSessionOpened(opened)
    }

    /**
     * For callers that did not establish the identity (launch, boot restore, OS callback). The id
     * is read under the lock that opens the session, so an identify cannot race the write.
     */
    fun beginUserSessionForCurrentUser() {
        val opened = synchronized(controllerLock) {
            openSessionLocked { store.beginUserSessionForCurrentUser(secureUserStore::getUserId) }
        }
        onSessionOpened(opened)
    }

    @VisibleForTesting
    internal fun holdsControllerLock(): Boolean = Thread.holdsLock(controllerLock)

    private fun reportDiscardedArrivals(polygonIds: Set<String>) {
        polygonIds.forEach { id ->
            logger.logPolygonArrivalExpired(id, PolygonArrivalExpiry.SESSION_ENDED)
        }
    }

    /**
     * Queues one observational record, only for the session [expectedUserStateGeneration] names; it
     * decides nothing. Call outside [controllerLock]. [diagnosticToken] defaults to the call; a record
     * answering an await passes the token taken before it, so a reset since retires the record rather
     * than filing it in the next session's journal.
     */
    private fun recordDiagnostic(
        expectedUserStateGeneration: Long,
        event: DwellDiagnosticEvent,
        fenceId: String?,
        fix: Location? = null,
        reason: DwellDiagnosticReason? = null,
        diagnosticToken: Long? = diagnosticRecorder?.currentToken()
    ) {
        val recorder = diagnosticRecorder ?: return
        val token = diagnosticToken ?: return
        // Read after the token was taken: a reset between the two retires the token instead.
        val current = synchronized(controllerLock) {
            hasMatchingIdentifiedUserLocked() && store.userStateGeneration() == expectedUserStateGeneration
        }
        if (!current) return
        val now = SystemClock.elapsedRealtime()
        val fixElapsedNanos = fix?.elapsedRealtimeNanos?.takeIf { it > 0L }
        recorder.record(
            DwellDiagnosticRecord(
                event = event,
                atElapsedMs = now,
                fenceId = fenceId,
                reason = reason,
                provider = fix?.provider,
                returnedAccuracyMeters = fix?.takeIf(Location::hasAccuracy)?.accuracy?.toDouble(),
                fixAgeMs = fixElapsedNanos?.let { now - it / NANOS_PER_MILLI }?.takeIf { it >= 0L },
                fixElapsedNanos = fixElapsedNanos
            ),
            token
        )
    }

    /**
     * Under [controllerLock], straight after a teardown. Retires diagnostics only when the teardown
     * really ended a session, so a no-op teardown keeps the current session's records.
     */
    private fun retireDiagnosticsIfSessionEndedLocked(previousGeneration: Long) {
        if (store.userStateGeneration() != previousGeneration) diagnosticRecorder?.invalidateAndClear()
    }

    /** What opening a session did, decided under the lock that opened it. */
    private class OpenedSession(val generationChanged: Boolean, val discarded: Set<String>)

    private fun openSessionLocked(open: () -> Unit): OpenedSession {
        val previousGeneration = store.userStateGeneration()
        open()
        // The generation moves only when the session changed, so only then is anything orphaned.
        if (store.userStateGeneration() == previousGeneration) {
            return OpenedSession(generationChanged = false, discarded = emptySet())
        }
        val discarded = engine.stop()
        approachMonitor.stop(previousGeneration)
        lastCoarseTransitionElapsedNanos.clear()
        forgetRequestedFix()
        // Before the lock is released, so no record admitted afterwards lands in the ended session.
        diagnosticRecorder?.invalidateAndClear()
        return OpenedSession(generationChanged = true, discarded = discarded)
    }

    /**
     * Outside [controllerLock]. Only a moved generation re-plans checks: every geofence callback first
     * opens the session for the current user, and an unchanged one must not churn WorkManager.
     */
    private fun onSessionOpened(opened: OpenedSession) {
        if (opened.generationChanged) dwellCheckScheduler?.reconcile()
        reportDiscardedArrivals(opened.discarded)
    }

    fun publishRegistrationIfCurrent(
        expectedUserStateGeneration: Long,
        userId: String,
        publish: () -> Unit
    ): Boolean = synchronized(controllerLock) {
        if (secureUserStore.getUserId() != userId ||
            store.activeUserSessionId() != userId ||
            store.userStateGeneration() != expectedUserStateGeneration
        ) {
            return@synchronized false
        }
        publish()
        true
    }

    private fun isCurrentRegisteredPolygon(
        polygonId: String,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int?
    ): Boolean = synchronized(controllerLock) {
        isCurrentRegisteredPolygonLocked(polygonId, expectedUserStateGeneration, expectedRegionRevision)
    }

    private fun isCurrentRegisteredPolygonLocked(
        polygonId: String,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int?
    ): Boolean {
        val currentRegion = store.getCachedRegion(polygonId)
        return hasMatchingIdentifiedUserLocked() &&
            store.userStateGeneration() == expectedUserStateGeneration &&
            polygonId in store.getRoutableRegisteredIds() &&
            currentRegion?.isPolygon == true &&
            (expectedRegionRevision == null || currentRegion.transitionRevision() == expectedRegionRevision)
    }

    private fun hasMatchingIdentifiedUserLocked(): Boolean {
        val currentUserId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() } ?: return false
        return store.activeUserSessionId() == currentUserId
    }

    /**
     * Asks for a precise fix when the delivered one decided nothing or left an arrival held. The
     * delivered fix is imprecise, not stale, so if no precise fix arrives its verdict stands.
     */
    private suspend fun evaluateCallbackFix(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long
    ) {
        // Taken before any await, so a reset while a request is out retires what its answer records.
        val diagnosticToken = diagnosticRecorder?.currentToken()
        fun recordDwellRejected(polygonIds: Set<String>, reason: DwellDiagnosticReason) = polygonIds.forEach {
            recordDiagnostic(
                expectedUserStateGeneration,
                DwellDiagnosticEvent.DWELL_REJECTED,
                fenceId = it,
                reason = reason,
                diagnosticToken = diagnosticToken
            )
        }
        if (triggeringLocation == null) {
            // This request serves the edge, so it is never charged to a dwell budget.
            val only = preciseFixForCallback(
                polygonId,
                undecidedPolygonIds = setOf(polygonId),
                pendingArrivalPolygonIds = emptySet(),
                triggeringLocation = null
            ) ?: return
            evaluateAndRecentre(only.fix, expectedUserStateGeneration)
            return
        }
        val delivered = evaluateAndRecentre(triggeringLocation, expectedUserStateGeneration)
        // GMS often answers with this callback's own fix; answersHeldFixAt lets the engine settle a
        // hold with it as not independent instead of skipping it as not newer.
        // A due visit the fix kept INSIDE without showing it is memoised like an undecided fence, so a
        // parked device asks once per spot per FUTILE_ESCALATION_RETRY_MS rather than every wake.
        val unresolved = delivered.undecidedPolygonIds + delivered.dwellUnprovenPolygonIds
        val needing = unresolved + delivered.pendingArrivalPolygonIds
        if (needing.isEmpty()) return
        // The fences that need this request for their dwell proof alone. One whose membership is still
        // undecided needs it for the edge as well, so it is neither budgeted nor charged.
        val dwellServed = delivered.dwellUnprovenPolygonIds - delivered.undecidedPolygonIds
        val dwellBudgeted = withinDwellProofBudget(dwellServed)
        recordDwellRejected(dwellServed - dwellBudgeted.keys, DwellDiagnosticReason.BUDGET_EXHAUSTED)
        val unresolvedInBudget = unresolved - (dwellServed - dwellBudgeted.keys)
        if (unresolvedInBudget.isEmpty() && delivered.pendingArrivalPolygonIds.isEmpty()) return
        // A request membership or a held arrival needs anyway goes out uncharged; its answer can still
        // prove a dwell. Only a request for dwell proof alone spends budget, and only if the charge lands.
        val membershipNeedsIt = (unresolvedInBudget - dwellServed).isNotEmpty() ||
            delivered.pendingArrivalPolygonIds.isNotEmpty()
        val fresh = preciseFixForCallback(
            polygonId,
            unresolvedInBudget,
            delivered.pendingArrivalPolygonIds,
            triggeringLocation,
            onSkipped = { reason -> recordDwellRejected(dwellBudgeted.keys, reason) },
            onRequesting = { at -> membershipNeedsIt || chargeDwellProofRequests(dwellBudgeted, at) }
        ) ?: return
        val after = evaluateAndRecentre(
            fresh.fix,
            expectedUserStateGeneration,
            answersHeldFixAt = triggeringLocation.elapsedRealtimeNanos
        )
        // An aborted pass reports nothing undecided, so it must not clear the memos.
        if (!after.acceptedFix) return
        // An answer that echoes this callback's own fix is skipped as not newer and decides nothing.
        // A fence whose verdict still rests on that fix learned nothing better, so it is futile too.
        // Held arrivals are never echoes, because answersHeldFixAt settles them.
        val echoed = after.echoedFixPolygonIds intersect unresolved
        // Measurement only, for what a precise answer still could not prove; the memo below decides retries.
        val stillUnproven = dwellServed intersect after.dwellUnprovenPolygonIds
        recordDwellRejected(stillUnproven, DwellDiagnosticReason.DWELL_UNPROVEN)
        recordDwellRejected(
            (dwellServed intersect (after.undecidedPolygonIds + echoed)) - stillUnproven,
            DwellDiagnosticReason.STILL_UNDECIDED
        )
        recordEscalationOutcomes(
            requested = needing,
            // Still unproven is futile too: clearing its memo would re-ask from the same spot next wake.
            stillUndecided = after.undecidedPolygonIds + after.dwellUnprovenPolygonIds + echoed,
            evaluated = after.evaluatedPolygonIds,
            fix = fresh.fix,
            atElapsedMs = SystemClock.elapsedRealtime(),
            requestSessionElapsedMs = fresh.requestSessionElapsedMs
        )
    }

    /**
     * An approach sample can be the only fix an entered polygon sees for minutes, so one that keeps
     * a due visit INSIDE without proving the stay asks for a precise fix, as a callback's fix would
     * (see [evaluateCallbackFix]). Dwell proof only: an undecided arrival keeps the sampler's own
     * hold-and-corroborate rule. The request shares the callback cooldown and the futile memo, so a
     * parked device asks once per spot per [FUTILE_ESCALATION_RETRY_MS], not on every sample.
     *
     * @return the precise fix when its pass accepted it, for the caller's movement re-centre; the
     * sampler re-centres once at the end of the batch, so this pass must not do it itself.
     */
    private suspend fun requestDwellProofForSample(
        sample: Location,
        delivered: PolygonEvaluationOutcome,
        expectedUserStateGeneration: Long
    ): Location? {
        val budgeted = withinDwellProofBudget(delivered.dwellUnprovenPolygonIds)
        val unproven = budgeted.keys
        if (unproven.isEmpty()) return null
        val diagnosticToken = diagnosticRecorder?.currentToken()
        fun recordDwellRejected(polygonIds: Set<String>, reason: DwellDiagnosticReason) = polygonIds.forEach {
            recordDiagnostic(
                expectedUserStateGeneration,
                DwellDiagnosticEvent.DWELL_REJECTED,
                fenceId = it,
                reason = reason,
                diagnosticToken = diagnosticToken
            )
        }
        val fresh = preciseFixForCallback(
            unproven.first(),
            undecidedPolygonIds = unproven,
            pendingArrivalPolygonIds = emptySet(),
            triggeringLocation = sample,
            onSkipped = { reason -> recordDwellRejected(unproven, reason) },
            onRequesting = { at -> chargeDwellProofRequests(budgeted, at) }
        ) ?: return null
        val after = processTriggeredLocation(
            fresh.fix,
            expectedUserStateGeneration,
            answersHeldFixAt = sample.elapsedRealtimeNanos
        )
        // An aborted pass reports nothing undecided, so it must not clear the memos.
        if (!after.acceptedFix) return null
        val echoed = after.echoedFixPolygonIds intersect unproven
        val stillUnproven = unproven intersect after.dwellUnprovenPolygonIds
        recordDwellRejected(stillUnproven, DwellDiagnosticReason.DWELL_UNPROVEN)
        recordDwellRejected(
            (unproven intersect (after.undecidedPolygonIds + echoed)) - stillUnproven,
            DwellDiagnosticReason.STILL_UNDECIDED
        )
        recordEscalationOutcomes(
            requested = unproven,
            stillUndecided = after.undecidedPolygonIds + after.dwellUnprovenPolygonIds + echoed,
            evaluated = after.evaluatedPolygonIds,
            fix = fresh.fix,
            atElapsedMs = SystemClock.elapsedRealtime(),
            requestSessionElapsedMs = fresh.requestSessionElapsedMs
        )
        return fresh.fix
    }

    /**
     * The polygons in [polygonIds] whose stored visit may still spend a precise request on its dwell
     * proof now (see [GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT]), each with the id of the
     * visit the budget belongs to, so a charge can never land on a visit that replaced it meanwhile.
     * A polygon with no stored visit has nothing to prove and is dropped.
     */
    private fun withinDwellProofBudget(polygonIds: Set<String>): Map<String, String> {
        if (polygonIds.isEmpty()) return emptyMap()
        val now = SystemClock.elapsedRealtime()
        return polygonIds.mapNotNull { id ->
            store.getDwellVisit(id)?.takeIf { dwellProofBudgetAllows(it, now) }?.let { id to it.visitId }
        }.toMap()
    }

    /**
     * Charges one request to each visit in [budgeted] (polygon id to visit id), persisted before the
     * request is issued, so neither a timeout nor a relaunch gives it back. Logged once per visit, on
     * its last request. True when at least one charge landed: a request none could pay for is not made.
     * A charge moves the visit's next background check past the spacing, so the checks are re-planned.
     */
    private fun chargeDwellProofRequests(budgeted: Map<String, String>, atElapsedMs: Long): Boolean {
        var charged = false
        budgeted.forEach { (id, visitId) ->
            if (!store.reserveDwellProofRequest(
                    geofenceId = id,
                    visitId = visitId,
                    atElapsedMs = atElapsedMs,
                    maxRequests = GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT,
                    minSpacingMs = GeofenceConstants.DWELL_PROOF_REQUEST_SPACING_MS
                )
            ) {
                return@forEach
            }
            charged = true
            val spent = store.getDwellVisit(id)?.takeIf { it.visitId == visitId }?.dwellProofRequests
            if (spent != null && spent >= GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT) {
                logger.logDwellProofBudgetSpent(id, GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT)
            }
        }
        if (charged) dwellCheckScheduler?.reconcile()
        return charged
    }

    private suspend fun evaluateAndRecentre(
        fix: Location,
        expectedUserStateGeneration: Long,
        answersHeldFixAt: Long? = null,
        expectedTeardownGeneration: Long? = null
    ): PolygonEvaluationOutcome {
        // Read together with the teardown check under one lock. Every teardown stops the engine under
        // that lock, so the epoch belongs to the session this token names. The engine then refuses the
        // pass if a teardown lands while the pass waits for its mutex or for the outbox recovery.
        val engineStopEpoch = if (expectedTeardownGeneration == null) {
            null
        } else {
            synchronized(controllerLock) {
                if (isAfterTeardownLocked(expectedTeardownGeneration)) null else engine.stopEpoch()
            } ?: return PolygonEvaluationOutcome.NOTHING
        }
        val outcome = processTriggeredLocation(
            fix,
            expectedUserStateGeneration,
            answersHeldFixAt,
            expectedEngineStopEpoch = engineStopEpoch
        )
        if (outcome.acceptedFix && !isAfterTeardown(expectedTeardownGeneration)) {
            updateMovementTriggerFromAcceptedFix(fix, expectedUserStateGeneration, expectedTeardownGeneration)
        }
        return outcome
    }

    /**
     * Rate-limited because one GMS batch dispatches each polygon separately; within the cooldown a
     * young fix already obtained answers later callbacks, so a batch costs one request.
     */
    private suspend fun preciseFixForCallback(
        polygonId: String,
        undecidedPolygonIds: Set<String>,
        pendingArrivalPolygonIds: Set<String>,
        triggeringLocation: Location?,
        /** Told why no fix was obtained, outside [controllerLock]. Observational: it decides nothing. */
        onSkipped: (DwellDiagnosticReason) -> Unit = {},
        /** Checked atomically with the request decision. */
        expectedTeardownGeneration: Long? = null,
        /**
         * Asked, outside [controllerLock], when a new request is about to be issued (not on a reuse,
         * cooldown or memo refusal), with its elapsedRealtime: the moment a budget is charged. False
         * cancels the request before it is made, and gives back the cooldown it would have started.
         */
        onRequesting: (requestedAtElapsedMs: Long) -> Boolean = { true }
    ): CallbackFix? {
        val needingPolygonIds = undecidedPolygonIds + pendingArrivalPolygonIds
        val requestedAt = SystemClock.elapsedRealtime()
        var cooldownBefore: Long? = null
        var requestTeardownGeneration = 0L
        val decision = synchronized(controllerLock) {
            if (isAfterTeardownLocked(expectedTeardownGeneration)) return null
            val reusable = lastFreshFix?.takeIf {
                (it.fixAgeMsOrNull(requestedAt) ?: Long.MAX_VALUE) <= BATCH_REUSE_WINDOW_MS
            }
            val previous = lastFreshFixRequestElapsedMs
            when {
                // Reuse first: a fix in hand costs nothing, so no suppression should refuse it.
                reusable != null -> CallbackFixDecision.Reuse(reusable, previous)
                previous != null && requestedAt - previous < FRESH_FIX_COOLDOWN_MS ->
                    CallbackFixDecision.WithinCooldown
                requestWouldRepeatItselfLocked(
                    needingPolygonIds,
                    pendingArrivalPolygonIds,
                    triggeringLocation,
                    requestedAt
                ) -> CallbackFixDecision.UnchangedPosition
                else -> {
                    cooldownBefore = previous
                    requestTeardownGeneration = teardownGeneration
                    lastFreshFixRequestElapsedMs = requestedAt
                    CallbackFixDecision.Request
                }
            }
        }
        when (decision) {
            is CallbackFixDecision.Reuse ->
                return CallbackFix(decision.fix, decision.requestSessionElapsedMs)
            CallbackFixDecision.WithinCooldown -> {
                logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.WITHIN_COOLDOWN)
                onSkipped(DwellDiagnosticReason.WITHIN_COOLDOWN)
                return null
            }
            CallbackFixDecision.UnchangedPosition -> {
                logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
                onSkipped(DwellDiagnosticReason.UNCHANGED_POSITION)
                return null
            }
            CallbackFixDecision.Request -> Unit
        }
        if (!onRequesting(requestedAt)) {
            // Only while this request still owns the stamp: no teardown cleared it, and no later request
            // (one started in the same millisecond after a teardown included) has stamped its own.
            synchronized(controllerLock) {
                if (teardownGeneration == requestTeardownGeneration && lastFreshFixRequestElapsedMs == requestedAt) {
                    lastFreshFixRequestElapsedMs = cooldownBefore
                }
            }
            onSkipped(DwellDiagnosticReason.BUDGET_EXHAUSTED)
            return null
        }
        logger.logPolygonFreshFixRequested(needingPolygonIds.sorted())
        val fix = freshFixSource.awaitFreshFix(FRESH_FIX_TIMEOUT_MS)
        if (fix == null) {
            logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.NONE_ARRIVED)
            onSkipped(DwellDiagnosticReason.NONE_ARRIVED)
            // Memoised so a device whose precise fix never arrives stops asking every wake. Held
            // arrivals are left out: the memo would outlast the hold and lose the arrival.
            if (triggeringLocation != null) {
                recordEscalationOutcomes(
                    requested = undecidedPolygonIds,
                    stillUndecided = undecidedPolygonIds,
                    evaluated = undecidedPolygonIds,
                    fix = triggeringLocation,
                    atElapsedMs = SystemClock.elapsedRealtime(),
                    requestSessionElapsedMs = requestedAt
                )
            }
            return null
        }
        // Only if still the request in flight, or a reset during the await would leave the
        // previous user's fix up for reuse.
        synchronized(controllerLock) {
            if (lastFreshFixRequestElapsedMs == requestedAt) lastFreshFix = fix
        }
        logger.logPolygonFreshFixReceived(
            location = fix,
            waitedSeconds = (SystemClock.elapsedRealtime() - requestedAt) / MILLIS_PER_SECOND
        )
        return CallbackFix(fix, requestSessionElapsedMs = requestedAt)
    }

    /**
     * Suppresses only when every fence the request serves is memoised. An open hold bypasses it:
     * a same-position fix still settles a held arrival, and without it the visit is lost.
     */
    private fun requestWouldRepeatItselfLocked(
        needingPolygonIds: Set<String>,
        pendingArrivalPolygonIds: Set<String>,
        triggeringLocation: Location?,
        nowElapsedMs: Long
    ): Boolean = pendingArrivalPolygonIds.isEmpty() &&
        needingPolygonIds.isNotEmpty() &&
        needingPolygonIds.all {
            escalationWouldRepeatItselfLocked(it, triggeringLocation, nowElapsedMs)
        }

    /**
     * The cooldown is shorter than the wake cadence, so this stops a parked device asking on every
     * wake: a fix from the same spot decides no better. [FUTILE_ESCALATION_RETRY_MS] still lets
     * one through, since a stationary device can still get a precise fix.
     */
    private fun escalationWouldRepeatItselfLocked(
        polygonId: String,
        triggeringLocation: Location?,
        nowElapsedMs: Long
    ): Boolean {
        val previous = futileEscalations[polygonId] ?: return false
        if (nowElapsedMs - previous.atElapsedMs >= FUTILE_ESCALATION_RETRY_MS) return false
        val current = triggeringLocation ?: return false
        // Within the looser fix's accuracy, movement is indistinguishable from noise.
        val tolerance = maxOf(previous.fix.accuracy, current.accuracy)
        return current.distanceTo(previous.fix) <= tolerance
    }

    /**
     * Records every polygon the request served, not just the callback's own, since one that
     * [onMovementTriggerExit] activated has no callback of its own.
     */
    private fun recordEscalationOutcomes(
        requested: Set<String>,
        stillUndecided: Set<String>,
        evaluated: Set<String>,
        fix: Location,
        atElapsedMs: Long,
        requestSessionElapsedMs: Long?
    ) = synchronized(controllerLock) {
        // Only while this request is still in flight, so it cannot undo a teardown's clear.
        // Keyed on the request, not the generation: a reset need not advance a generation.
        if (requestSessionElapsedMs == null || lastFreshFixRequestElapsedMs != requestSessionElapsedMs) {
            return@synchronized
        }
        requested.forEach { id ->
            when {
                id in stillUndecided -> futileEscalations[id] = FutileEscalation(fix, atElapsedMs)
                id in evaluated -> futileEscalations.remove(id)
                // Not newer, and not an echo of the fix its verdict rests on, so it says nothing about this spot.
                else -> Unit
            }
        }
    }

    /** Null when the fix has no monotonic timestamp. */
    private fun Location.fixAgeMsOrNull(nowElapsedMs: Long): Long? =
        elapsedRealtimeNanos.takeIf { it > 0L }?.let { nowElapsedMs - it / NANOS_PER_MILLI }

    private suspend fun processTriggeredLocation(
        location: Location,
        expectedUserStateGeneration: Long,
        answersHeldFixAt: Long? = null,
        expectedEngineStopEpoch: Long? = null
    ): PolygonEvaluationOutcome = engine.processResponsiveLocation(
        location,
        expectedUserStateGeneration,
        answersHeldFixAt,
        expectedEngineStopEpoch
    )

    // replaceMovementTrigger checks the permission itself and fails when it is missing.
    @SuppressLint("MissingPermission")
    private suspend fun updateMovementTriggerFromAcceptedFix(
        location: Location,
        expectedUserStateGeneration: Long,
        expectedTeardownGeneration: Long? = null
    ): Float? {
        val fix = location.toPolygonLocationFix() ?: return null
        val registeredPolygons = store.getRoutableRegisteredIds().mapNotNull { id ->
            store.getCachedRegion(id)?.takeIf(GeofenceRegion::isPolygon)
        }
        val normalRadius = store.getCachedConfigOrFallback().localRefreshTriggerRadius
        val safeRadius = movementTriggerPolicy.safeRadiusMeters(
            regions = registeredPolygons,
            committedInsideIds = store.getEnteredIds(),
            sample = fix.sample,
            normalRadiusMeters = normalRadius
        ) ?: return null
        val trigger = GeofenceRegion(
            id = GeofenceConstants.MOVEMENT_TRIGGER_ID,
            latitude = location.latitude,
            longitude = location.longitude,
            radius = safeRadius,
            transitionTypes = listOf(GeofenceTransitionType.EXIT)
        )
        val result = manager.replaceMovementTrigger(trigger)
        if (result.isFailure) return null
        // Generation-checked: a sign-out may have completed while the registration awaited GMS.
        val recorded = store.saveLastMovementTriggerLocationIfCurrent(
            GeofenceLocation(location.latitude, location.longitude),
            safeRadius,
            expectedUserStateGeneration
        )
        if (!recorded) return null
        // Recorded either way, as GMS now holds it; but a teardown during the await may have handed
        // sampling to a re-activated session, which this stop would end.
        if (isAfterTeardown(expectedTeardownGeneration)) return null
        approachMonitor.stop(expectedUserStateGeneration)
        return safeRadius
    }

    private fun acceptCoarseTransition(polygonId: String, location: Location?): Boolean =
        synchronized(controllerLock) {
            val elapsedRealtimeNanos = location?.elapsedRealtimeNanos?.takeIf { it > 0L }
                ?: return@synchronized true
            val previous = lastCoarseTransitionElapsedNanos[polygonId]
            if (previous != null && elapsedRealtimeNanos <= previous) return@synchronized false
            lastCoarseTransitionElapsedNanos[polygonId] = elapsedRealtimeNanos
            true
        }

    private fun osStateWasWiped(): Boolean {
        val rebooted = store.registrationsPredateBoot(bootSessionProvider.currentSessionId(), SystemClock.elapsedRealtime())
        val currentPackageUpdate = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrNull()
        val replaced = store.getLastRegistrationPackageUpdateTime()?.let { stamped ->
            currentPackageUpdate != null && currentPackageUpdate != stamped
        } == true
        return rebooted || replaced
    }
}

internal enum class PolygonSamplingDecision {
    CONTINUE,
    STOP,
    STALE
}
