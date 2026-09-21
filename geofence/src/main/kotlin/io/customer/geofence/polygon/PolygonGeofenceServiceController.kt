package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionType
import io.customer.geofence.PolygonArrivalExpiry
import io.customer.geofence.PolygonCallbackDrop
import io.customer.geofence.PolygonFreshFixSkip
import io.customer.geofence.PolygonSamplingSkip
import io.customer.geofence.distanceTo
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.getCachedConfigOrFallback
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
    private val manager: GeofenceManager,
    private val secureUserStore: SecureUserStore,
    private val freshFixSource: PolygonFreshFixSource,
    private val recheckScheduler: PolygonRecheckScheduler,
    private val passiveMonitor: PolygonPassiveMonitor,
    private val logger: GeofenceLogger
) {
    private val movementTriggerPolicy = PolygonMovementTriggerPolicy()
    private val controllerLock = Any()

    /** @see preciseFixForCallback */
    private var lastFreshFixRequestElapsedMs: Long? = null
    private var lastFreshFix: Location? = null

    /**
     * Drops the requested fix and its rate limit, wherever [lastCoarseTransitionElapsedNanos] is
     * dropped.
     *
     * Both are user-scoped even though neither is keyed by a user. A fix taken under one identity
     * is a real position, but letting it decide an arrival under the next is the carry-over every
     * other memo in this class is cleared to prevent. The rate limit matters more: a sign-out
     * leads to a sync and a registration with `INITIAL_TRIGGER_ENTER`, so the next user's first
     * callback arrives within seconds and would otherwise be refused its own fix because the
     * previous session asked for one.
     */
    /**
     * Bumped by every teardown, so a scheduled wake already past its wait cannot re-arm what the
     * teardown removed.
     *
     * Cancellation cannot enforce this boundary on its own: a worker suspended in `awaitFreshFix`
     * resumes and reaches [activate] with the routable ids and user generation that teardown leaves
     * in place on purpose, so neither of those reads can tell it its session is over.
     */
    private var teardownGeneration: Long = 0L

    /** Captured by a scheduled wake before it waits, and handed back to [activate] afterwards. */
    fun teardownGeneration(): Long = synchronized(controllerLock) { teardownGeneration }

    private fun isAfterTeardown(expected: Long?): Boolean = expected != null &&
        synchronized(controllerLock) { isAfterTeardownLocked(expected) }

    /**
     * For callers already inside [controllerLock], so the check cannot be separated from the state
     * it guards. Every teardown bumps the generation under this same lock, which is what makes a
     * check made inside it one no teardown can land behind.
     *
     * The matching checks made before taking the lock are a fast path, not a guard: removing one
     * changes what a dead wake costs on its way to being refused, not whether it is refused. They
     * save the registration read and the dedupe memo write that would otherwise happen first.
     */
    private fun isAfterTeardownLocked(expected: Long?): Boolean =
        expected != null && expected != teardownGeneration

    private fun forgetRequestedFix() {
        lastFreshFix = null
        lastFreshFixRequestElapsedMs = null
        teardownGeneration += 1
        futileEscalations.clear()
    }

    /**
     * A precise fix that was requested for [polygonId] and still could not decide it, with the
     * position it was taken from. Guarded by [controllerLock], cleared through
     * [forgetRequestedFix] so every teardown path already covers it.
     */
    private data class FutileEscalation(
        val fix: Location,
        val atElapsedMs: Long
    )

    private val futileEscalations = mutableMapOf<String, FutileEscalation>()
    private val coarseTransitionMutex = Mutex()
    private val lastCoarseTransitionElapsedNanos = mutableMapOf<String, Long>()

    /**
     * Reached on every coarse ENTER, including the ones an identify manufactures.
     *
     * Opening a session re-arms routing from empty, so nothing counts as unchanged afterwards and
     * GMS re-reports ENTER for every fence the device is already inside. The attached fix is
     * evaluated first and a decisive one ends the session immediately, so the two-minute cost lands
     * only when that fix is ambiguous — but for an app that identifies often, that is a recurring
     * cost with no movement behind it.
     *
     * It is not suppressible without weakening the guarantee: the identify cleared containment,
     * circles recover theirs from synthesis at registration, and a polygon cannot, because the sync
     * fix carries no accuracy evidence. Suppressing here would mean a newly identified user
     * standing inside a ring never gets its ENTER until they move.
     */
    suspend fun activate(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        expectedRegionRevision: Int? = null,
        expectedTeardownGeneration: Long? = null
    ) = coarseTransitionMutex.withLock {
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
        // The token goes through to the locked activation as well. The check above runs before the
        // registration and dedupe reads, none of which hold the lock, so a teardown landing in that
        // gap used to meet no further check and re-armed the polygon it had just removed.
        val armed = activate(
            polygonId,
            expectedUserStateGeneration,
            expectedRegionRevision,
            expectedTeardownGeneration
        )
        if (!armed) return@withLock
        evaluateCallbackFix(polygonId, triggeringLocation, expectedUserStateGeneration)
    }

    /** Returns whether the polygon was armed, so a caller can skip work that assumed it was. */
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
        // Outside the lock: the host's log dispatcher is customer code.
        reportDiscardedArrivals(discarded)
        if (!routable) {
            logger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, polygonId)
        }
        return routable
    }

    /**
     * Tears a polygon's coarse session down. Callers hold [controllerLock] and report the returned
     * arrival holds once they have left it.
     *
     * Returning the holds rather than logging them is what keeps the record lock-free. The public
     * wrapper this replaces could not: `synchronized` is reentrant, so [onCoarseExit] calling it
     * from inside the lock reported the discards when only the inner block had closed, and
     * `session_ended` reached the host dispatcher under the outer lock.
     */
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
     * Takes a teardown token for the same reason [activate] does, which is easy to miss on a path
     * whose name says departure. The tail of this function re-arms: a polygon still in the entered
     * set stays active rather than being deactivated, and teardown retains the entered set on
     * purpose, so a departure dispatched before a teardown and run after it would put the polygon
     * back into the active set and restart approach sampling.
     */
    suspend fun onCoarseExit(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        expectedRegionRevision: Int? = null,
        expectedTeardownGeneration: Long? = null
    ) = coarseTransitionMutex.withLock {
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
            // Checked here as well as at the tail, because these are different windows. The tail
            // guards the re-arm; this guards the write below and the fix evaluation after it,
            // which can open the GPS for a polygon whose session is already over.
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
            // Re-checked under the lock: evaluateCallbackFix above can suspend, so a teardown that
            // was current when this started may have landed by now.
            if (isAfterTeardownLocked(expectedTeardownGeneration)) return@synchronized emptySet()
            if (!isCurrentRegisteredPolygonLocked(
                    polygonId,
                    expectedUserStateGeneration,
                    expectedRegionRevision
                )
            ) {
                return@synchronized emptySet()
            }
            // A catalog refresh can activate from a newer live fix while the triggering fix is
            // evaluated. Never let this older EXIT tear that newer session down.
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

    /** Evaluates the accepted fix that fired the SDK-wide shared movement trigger. */
    suspend fun onMovementTriggerExit(
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ): Float? {
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
            // Split deliberately. "No polygon in range" is routine and would otherwise fill the
            // capture, hiding the two state failures inside it — and a lost arrival caused by a
            // generation mismatch on this path would read as routine.
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
        // Outside the lock, like every other outward call here: WorkManager does disk work.
        //
        // Keyed on what is REGISTERED, not on what is active. An active polygon is one already
        // woken, and the re-check exists for the polygon that never woke at all, so gating on the
        // active set would switch the thing off in exactly the case it is for.
        if (ids.isEmpty()) {
            stopScheduledWakes()
        } else {
            // Both wakes share this lifetime. The passive listener costs nothing to hold open and
            // guarantees nothing, so it is the cheaper half of the same answer: it can land inside
            // the re-check's interval, and on a device where no other app asks for location it
            // simply never fires.
            recheckScheduler.schedule()
            passiveMonitor.start()
        }
    }

    fun recover() {
        val discarded = synchronized(controllerLock) {
            when {
                !hasMatchingIdentifiedUserLocked() -> invalidatePersistedCoarseStateLocked()
                osStateWasWiped() -> invalidatePersistedCoarseStateLocked()
                else -> {
                    if (store.getActivePolygonIds().isEmpty()) {
                        approachMonitor.stop(store.userStateGeneration())
                    }
                    emptySet()
                }
            }
        }
        reportDiscardedArrivals(discarded)
    }

    /** Processes fixes from the bounded session opened by a polygon or movement-trigger wake. */
    suspend fun processApproachLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long,
        sessionDeadlineElapsedRealtimeMs: Long
    ): PolygonSamplingDecision {
        if (locations.isEmpty()) {
            logger.logPolygonSamplingSkipped(PolygonSamplingSkip.EMPTY_BATCH)
            return PolygonSamplingDecision.CONTINUE
        }
        val admitted = synchronized(controllerLock) {
            hasMatchingIdentifiedUserLocked() &&
                store.userStateGeneration() == expectedUserStateGeneration
        }
        if (!admitted) {
            // The one reason here that ends sampling outright rather than dropping a sample.
            logger.logPolygonSamplingSkipped(PolygonSamplingSkip.NOT_CURRENT_SESSION)
            return PolygonSamplingDecision.STALE
        }
        approachMonitor.recordSampleDelivered(sessionDeadlineElapsedRealtimeMs)
        var lastAcceptedLocation: Location? = null
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
                // The fix's own position inside the circle, with no accuracy margin. Requiring the
                // whole accuracy circle to fit made sense against a padded radius; against the
                // backend's own circle, which is only tens of metres wider than the ring, an
                // indoor fix could never satisfy it from anywhere, and six of the eleven catalogued
                // circles are 163 m or smaller.
                //
                // Reason about the cost together with the on-demand fix, which ships alongside
                // this: a false admission is evaluated, comes back undecided, and therefore buys a
                // high-accuracy request inside the broadcast budget. So it costs a bounded amount
                // of sensor time per spurious fix, not a sampling session. Against that, a missed
                // admission loses the visit outright, because nothing re-derives a polygon arrival.
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
            // Logged outside the lock: GeofenceLogger forwards to the host Logger, whose
            // dispatcher is customer code, and holding controllerLock across it would stall every
            // activate, deactivate and approach path on a slow lambda.
            if (staleBeforeProcessing) {
                logger.logPolygonSamplingSkipped(PolygonSamplingSkip.NOT_CURRENT_SESSION)
                return PolygonSamplingDecision.STALE
            }
            if (!shouldProcess) {
                logger.logPolygonSamplingSkipped(PolygonSamplingSkip.NO_POLYGON_IN_RANGE)
                continue
            }
            if (processTriggeredLocation(location, expectedUserStateGeneration).acceptedFix) {
                lastAcceptedLocation = location
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
        // Guarded like every other read of the active set. Unlocked, a reconcile or a deactivate
        // landing here flips the answer: CONTINUE with nothing active spends battery on nothing,
        // and STOP with a polygon still active loses its EXIT.
        //
        // Generation too, not just the lock. The trigger update above awaits GMS, and an identify
        // landing in that gap leaves this continuation reading the NEW user's active set. It would
        // answer CONTINUE, and the receiver would then start a session for the generation that has
        // already ended, replacing the approach request the new user just armed.
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
         * How long a dispatch may wait for a precise fix.
         *
         * **This spends the receiver's dispatch budget, and it is not the only claimant.**
         * `GeofenceBroadcastReceiver` holds its `goAsync` window open for
         * `DISPATCH_WAIT_BUDGET_MS`, 8 s, covering persistence, the GMS awaits and the
         * movement-refresh join, and the join is last. A batch naming both a polygon and the
         * movement trigger is a common shape in the captures, so this wait lands ahead of that
         * join.
         *
         * Sized against the refresh it competes with: callback to `movement.registered` measures
         * 0.1 s to 6.57 s over 22 observations, median 0.31 s. At three seconds the join still has
         * its full window for 21 of those 22, and the worst one loses part of a protection window
         * rather than the refresh itself, which runs on the longer-lived services scope either way.
         *
         * The cooldown bounds the total spend to one wait per dispatch: `lastFreshFix` is set only
         * on success, so a timeout leaves the rate limit armed and later fences in the batch skip.
         */
        const val FRESH_FIX_TIMEOUT_MS = 3_000L

        /** @see preciseFixForCallback */
        const val FRESH_FIX_COOLDOWN_MS = 30_000L

        /**
         * How long a futile escalation suppresses the next one for the same polygon from the same
         * position.
         *
         * **Unmeasured**, a starting value. 30 minutes turns the measured 190 requests a day into
         * at most 48 for a parked device, and the position check means a user who actually moves is
         * never delayed. Revisit from a capture that reports request spacing and whether a fix good
         * enough to decide ever arrives while stationary, not by reasoning.
         */
        const val FUTILE_ESCALATION_RETRY_MS = 30 * 60_000L

        /**
         * How long a fix already obtained may answer a later callback from the same GMS batch.
         *
         * Sized to the batch, which is measured: consecutive `os.callback.received` gaps inside one
         * delivery are 0.07 s to 0.36 s across seven captures, with the next gap above 4 s being a
         * separate wake. Two seconds is a 5x margin on the widest batch observed.
         *
         * Not 30 s. A reused fix bypasses the cooldown, and the engine's own fix-age ceiling is
         * 120 s, so a half-minute-old fix would pass every gate and decide an arrival from a
         * position the device may have left: at the 26 km/h measured on the 2026-09-18 drive, 25 s
         * is 180 m, against a wake circle of about 100 m. iOS's 30 s is a gate on a cache they
         * cannot bound, which is a different problem from this one.
         */
        const val BATCH_REUSE_WINDOW_MS = 2_000L

        const val MILLIS_PER_SECOND = 1_000.0
        const val NANOS_PER_MILLI = 1_000_000L
    }

    /** What the cooldown decided, resolved under the lock so two callbacks cannot both request. */
    /**
     * A fix for a callback, with the request session that produced it, or null when it came from
     * the batch-reuse window and no request of ours is in flight for it.
     */
    private data class CallbackFix(val fix: Location, val requestSessionElapsedMs: Long?)

    private sealed interface CallbackFixDecision {
        /**
         * [requestSessionElapsedMs] is the token of the request that produced [fix], read under
         * the same lock as the reuse decision. A reused fix is real evidence about every fence it
         * is judged against, so it has to stay recordable; without a token the write could not be
         * checked against a teardown and had to be taken on trust.
         */
        data class Reuse(val fix: Location, val requestSessionElapsedMs: Long?) : CallbackFixDecision
        data object Request : CallbackFixDecision
        data object WithinCooldown : CallbackFixDecision
        data object UnchangedPosition : CallbackFixDecision
    }

    /**
     * Stops both schedule-driven wakes. Every path that tears geofencing or the user session down
     * calls this, outside [controllerLock], because WorkManager does disk work and both log through
     * the host dispatcher.
     *
     * **Called before the state wipe, not after it, and the order is load-bearing.** Raised by
     * Shahroz on #897 with a reproduction. Teardown used to bump the teardown generation first and
     * cancel the passive registration last, which is the worst possible order: a delivery landing
     * in between captured the already-bumped token, so the token agreed with the teardown that had
     * just happened, while [PolygonPassiveMonitor.isArmed] still answered yes because the intent
     * was not cancelled yet. It passed both checks and re-armed the polygon after shutdown, with
     * the store wipe already behind it. His run returned from stopAll() with active ids [venue].
     *
     * Reversed, the two become one boundary. Cancelling the intent first shuts the gate
     * synchronously and permanently, so any delivery that arrives afterwards is refused by
     * isArmed() whatever the token says, including one the OS had already dispatched. A delivery
     * admitted just before the cancel can still activate, and that is harmless now: the wipe runs
     * after, so it clears what that delivery armed instead of being overwritten by it.
     *
     * Unconditional rather than keyed on what is still registered: [stopAll] and
     * [clearUserSessionRetainingOsRegistrations] leave registrations in place on purpose, so
     * reading them here would re-arm the wake the caller is removing.
     * [reconcileRegisteredPolygons] arms it again the next time registrations are written.
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
        // Before the wipe, not after it. See stopScheduledWakes.
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            val generation = store.userStateGeneration()
            store.saveRegisteredIds(emptySet())
            store.saveRoutableRegisteredIds(emptySet())
            store.saveRetainedRegisteredRegions(emptyList())
            store.clearActivePolygonIds()
            store.retainCoarseInsidePolygonIds(emptySet())
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(generation)
            holds
        }
        reportDiscardedArrivals(discarded)
    }

    fun stopAll() {
        // Before the wipe, not after it. See stopScheduledWakes.
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
        reportDiscardedArrivals(discarded)
    }

    fun clearUserScopedState() {
        // Before the wipe, not after it. See stopScheduledWakes.
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            // The generation/registration wipe shares this lock with coarse callbacks. A callback
            // that GMS queued before sign-out can therefore neither pass its generation check after
            // the wipe nor re-arm exact-location monitoring between the wipe and service teardown.
            val generation = store.userStateGeneration()
            store.clearUserScopedState()
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(generation)
            holds
        }
        reportDiscardedArrivals(discarded)
    }

    fun clearUserSessionRetainingOsRegistrations() {
        // Before the wipe, not after it. See stopScheduledWakes.
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            val generation = store.userStateGeneration()
            store.clearUserSessionRetainingOsRegistrations()
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(generation)
            holds
        }
        reportDiscardedArrivals(discarded)
    }

    fun completeUserReset(
        expectedUserStateGeneration: Long,
        osRegistrationsCleared: Boolean
    ) {
        // Before the wipe, not after it. See stopScheduledWakes.
        stopScheduledWakes()
        val discarded = synchronized(controllerLock) {
            store.completeUserReset(expectedUserStateGeneration, osRegistrationsCleared)
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(expectedUserStateGeneration)
            holds
        }
        reportDiscardedArrivals(discarded)
    }

    fun beginUserSession(userId: String) {
        val discarded = synchronized(controllerLock) {
            openSessionLocked { store.beginUserSession(userId) }
        }
        reportDiscardedArrivals(discarded)
    }

    /**
     * For callers acting on an identity they did not establish: app launch, boot restore, an OS
     * callback. The read is handed to the store so it happens under the lock that opens the
     * session, instead of racing an identify to the write.
     */
    fun beginUserSessionForCurrentUser() {
        val discarded = synchronized(controllerLock) {
            openSessionLocked { store.beginUserSessionForCurrentUser(secureUserStore::getUserId) }
        }
        reportDiscardedArrivals(discarded)
    }

    /**
     * Whether the calling thread holds [controllerLock]. See [PolygonLocationEngine.holdsStateLock]
     * for why this is a boolean and not the lock.
     */
    @VisibleForTesting
    internal fun holdsControllerLock(): Boolean = Thread.holdsLock(controllerLock)

    /**
     * Reports arrival holds that a teardown discarded, once controllerLock is released.
     *
     * The engine returns these ids rather than logging them itself: every teardown path reaches it
     * through this lock, and [GeofenceLogger] forwards to the host `Logger`, whose dispatcher is
     * customer code. A slow dispatcher holding this lock would stall every coarse callback.
     */
    private fun reportDiscardedArrivals(polygonIds: Set<String>) {
        polygonIds.forEach { id ->
            logger.logPolygonArrivalExpired(id, PolygonArrivalExpiry.SESSION_ENDED)
        }
    }

    private fun openSessionLocked(open: () -> Unit): Set<String> {
        val previousGeneration = store.userStateGeneration()
        open()
        // The generation moves only when the session actually changed, so it says whether the
        // previous session's sampling and approach request are now orphaned.
        if (store.userStateGeneration() == previousGeneration) return emptySet()
        val discarded = engine.stop()
        approachMonitor.stop(previousGeneration)
        lastCoarseTransitionElapsedNanos.clear()
        forgetRequestedFix()
        return discarded
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
     * Evaluates a coarse callback, and asks for a precise fix when the delivered one decided
     * nothing.
     *
     * Every fix this pipeline has ever evaluated came attached to a GMS callback, and its accuracy
     * is GMS's choice rather than ours: across four field captures it degrades to 100-1000 m when
     * the device is parked, which is above the arrival ceiling of every real fence we monitor. This
     * request is the only fix in the pipeline whose accuracy class the SDK picks.
     *
     * Undecided, not stale, is the trigger, and the difference is measured rather than assumed: the
     * attached fix has a median age of 0.6 s and a maximum of 9.7 s across 54 callbacks, so the age
     * gate iOS uses would never fire here. What goes wrong is accuracy, not freshness.
     *
     * A request that answers with nothing is not a failure to handle. The delivered verdict stands,
     * and the pair of records says which happened.
     */
    private suspend fun evaluateCallbackFix(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long
    ) {
        if (triggeringLocation == null) {
            // Nothing to judge and nothing to fall back on, so the request is the whole pass.
            // Unmeasured: every callback in seven captures carried a fix, 49 of 49 fixsrc=os_trigger
            // and no fixsrc=none, so this is fail-open surface rather than an observed case.
            val only = preciseFixForCallback(polygonId, setOf(polygonId), triggeringLocation = null)
                ?: return
            evaluateAndRecentre(only.fix, expectedUserStateGeneration)
            return
        }
        val delivered = evaluateAndRecentre(triggeringLocation, expectedUserStateGeneration)
        // A held arrival needs a fix for the opposite reason to an undecided one, and the same
        // request answers both. Batch reuse stays eligible: lastFreshFix only ever holds a previous
        // request's answer, never the triggering fix, so it is a separate measurement; and if it is
        // the very fix a hold is waiting on, the corroboration guard refuses it there instead.
        val needing = delivered.undecidedPolygonIds + delivered.pendingArrivalPolygonIds
        if (needing.isEmpty()) return
        val fresh = preciseFixForCallback(polygonId, needing, triggeringLocation) ?: return
        val after = evaluateAndRecentre(fresh.fix, expectedUserStateGeneration)
        // Only a pass that actually evaluated says anything about this position. An aborted one
        // reports nothing undecided without having decided, and reading that as "decided" cleared
        // every memo and let the parked loop resume on the next wake.
        if (!after.acceptedFix) return
        recordEscalationOutcomes(
            requested = needing,
            stillUndecided = after.undecidedPolygonIds,
            evaluated = after.evaluatedPolygonIds,
            fix = fresh.fix,
            atElapsedMs = SystemClock.elapsedRealtime(),
            requestSessionElapsedMs = fresh.requestSessionElapsedMs
        )
    }

    private suspend fun evaluateAndRecentre(
        fix: Location,
        expectedUserStateGeneration: Long
    ): PolygonEvaluationOutcome {
        val outcome = processTriggeredLocation(fix, expectedUserStateGeneration)
        if (outcome.acceptedFix) {
            updateMovementTriggerFromAcceptedFix(fix, expectedUserStateGeneration)
        }
        return outcome
    }

    /**
     * A precise fix for this callback, or null to evaluate whatever it delivered.
     *
     * Rate-limited rather than requested per callback. One GMS batch can report several polygons,
     * and the receiver dispatches each one separately, so an unbounded version would open the GPS
     * once per fence in the batch. Within the cooldown the fix already obtained answers the later
     * callbacks while it is still young, so a batch of undecided fences costs one request.
     */
    private suspend fun preciseFixForCallback(
        polygonId: String,
        needingPolygonIds: Set<String>,
        triggeringLocation: Location?
    ): CallbackFix? {
        val requestedAt = SystemClock.elapsedRealtime()
        val decision = synchronized(controllerLock) {
            val reusable = lastFreshFix?.takeIf {
                (it.fixAgeMsOrNull(requestedAt) ?: Long.MAX_VALUE) <= BATCH_REUSE_WINDOW_MS
            }
            val previous = lastFreshFixRequestElapsedMs
            when {
                // Reuse first: a fix already in hand costs nothing, so the suppression below has
                // no reason to refuse it.
                reusable != null -> CallbackFixDecision.Reuse(reusable, previous)
                // The cooldown stays ahead of the suppression below, so this only ever changes a
                // pass that would otherwise have asked. Reversing them would relabel a rate-limited
                // skip and change what a capture says about behaviour that was already correct.
                previous != null && requestedAt - previous < FRESH_FIX_COOLDOWN_MS ->
                    CallbackFixDecision.WithinCooldown
                escalationWouldRepeatItselfLocked(polygonId, triggeringLocation, requestedAt) ->
                    CallbackFixDecision.UnchangedPosition
                else -> {
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
                return null
            }
            CallbackFixDecision.UnchangedPosition -> {
                logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
                return null
            }
            CallbackFixDecision.Request -> Unit
        }
        logger.logPolygonFreshFixRequested(needingPolygonIds.sorted())
        val fix = freshFixSource.awaitFreshFix(FRESH_FIX_TIMEOUT_MS)
        if (fix == null) {
            logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.NONE_ARRIVED)
            // Nothing arrived, so the delivered fix is the best available statement of where this
            // was asked from, and the movement check reads it identically: it carries a position
            // and an accuracy. Without this a device whose precise fix never arrives keeps asking
            // on every wake, which is the same waste by a different route. The log stays
            // NONE_ARRIVED; only the memo changes.
            if (triggeringLocation != null) {
                recordEscalationOutcomes(
                    requested = needingPolygonIds,
                    stillUndecided = needingPolygonIds,
                    evaluated = needingPolygonIds,
                    fix = triggeringLocation,
                    atElapsedMs = SystemClock.elapsedRealtime(),
                    requestSessionElapsedMs = requestedAt
                )
            }
            return null
        }
        // Memoised only while this request is still the one in flight. A user-scope reset during
        // the await clears the memo deliberately, and storing the fix afterwards would repopulate
        // it with the previous user's position for the next user's callback to reuse inside the
        // batch window. That pass would abort on the generation check, so this is about
        // attribution rather than a wrong verdict, and attribution is what the reset is for. This
        // callback still gets its fix: it asked before the reset, and the fix is the device's real
        // position either way.
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
     * Whether asking for another precise fix would repeat one that already failed.
     *
     * Measured 2026-09-20: a device parked inside one polygon's 371 m wake circle produced **190
     * precise-fix requests in a day**, median 6 minutes apart, 99% of them returning exactly 100 m
     * and every one undecided. The wake sources are working as intended and the 30 s cooldown is
     * far shorter than the wake cadence, so nothing stopped it. Zero transitions came out of that
     * day.
     *
     * The discriminator is movement, not time: a fix taken from where the last futile one was
     * taken cannot separate inside from outside any better. [FUTILE_ESCALATION_RETRY_MS] lets one
     * through periodically anyway, because accuracy at a fixed position is bimodal on this hardware
     * (either ~1 m or exactly 100 m), so a stationary device does occasionally get a fix that would
     * decide.
     */
    private fun escalationWouldRepeatItselfLocked(
        polygonId: String,
        triggeringLocation: Location?,
        nowElapsedMs: Long
    ): Boolean {
        val previous = futileEscalations[polygonId] ?: return false
        if (nowElapsedMs - previous.atElapsedMs >= FUTILE_ESCALATION_RETRY_MS) return false
        val current = triggeringLocation ?: return false
        // Either fix's own error bounds how far the device can be shown to have moved, so the
        // looser of the two is the threshold. Below it, "moved" is indistinguishable from noise.
        val tolerance = maxOf(previous.fix.accuracy, current.accuracy)
        return current.distanceTo(previous.fix) <= tolerance
    }

    /**
     * One request answers every polygon in [requested], so the outcome is recorded for all of
     * them rather than for the callback's own id alone.
     *
     * **Unasserted, deliberately.** Recording only the callback's own id survives the whole test
     * class, because a polygon normally reaches [requested] by being active, activating it is its
     * own callback, and that callback already records it.
     *
     * One route does escape that: [onMovementTriggerExit] activates a polygon without passing
     * through the callback-fix path, so it can sit in another polygon's undecided set carrying no
     * record of its own. Even then the leak is one extra request, since its own next fallback wake
     * records it. Bounded rather than absent, which is why this records the set.
     */
    private fun recordEscalationOutcomes(
        requested: Set<String>,
        stillUndecided: Set<String>,
        evaluated: Set<String>,
        fix: Location,
        atElapsedMs: Long,
        requestSessionElapsedMs: Long?
    ) = synchronized(controllerLock) {
        // The write lands after awaitFreshFix suspended, and a teardown in that window clears
        // futileEscalations. Without this the continuation repopulated it with the previous
        // session's position, and the next session's first callback there skipped its own precise
        // fix for the whole retry window. Keyed on the in-flight request rather than the store
        // generation, exactly as the lastFreshFix memo is, because a reset that clears this need
        // not advance a generation.
        //
        // A reused fix reaches here with the token of the request that produced it, so the same
        // comparison covers it. Refusing to record it instead would have thrown away the batch
        // case this path exists for: one request answers every fence in the batch, and each of
        // them is genuinely undecided at this position. A null token means no request can be
        // named at all, which nothing should be written on.
        if (requestSessionElapsedMs == null || lastFreshFixRequestElapsedMs != requestSessionElapsedMs) {
            return@synchronized
        }
        requested.forEach { id ->
            when {
                id in stillUndecided -> futileEscalations[id] = FutileEscalation(fix, atElapsedMs)
                // A fix that decided says this position is answerable after all.
                id in evaluated -> futileEscalations.remove(id)
                // Judged by neither branch: the route processor skipped this fence because the fix
                // was not strictly newer than the one it last saw for it, so the pass says nothing
                // about this position. Clearing here read that silence as a decision and let the
                // parked loop resume on the next wake, which is the waste this whole path exists
                // to stop.
                else -> Unit
            }
        }
    }

    /** Null when the fix carries no monotonic timestamp, which is the same as carrying no age. */
    private fun Location.fixAgeMsOrNull(nowElapsedMs: Long): Long? =
        elapsedRealtimeNanos.takeIf { it > 0L }?.let { nowElapsedMs - it / NANOS_PER_MILLI }

    private suspend fun processTriggeredLocation(
        location: Location,
        expectedUserStateGeneration: Long
    ): PolygonEvaluationOutcome =
        engine.processResponsiveLocation(location, expectedUserStateGeneration)

    // Reached only from a delivered GMS geofence callback, which the OS makes only for a
    // registration that already held the permission.
    @SuppressLint("MissingPermission")
    private suspend fun updateMovementTriggerFromAcceptedFix(
        location: Location,
        expectedUserStateGeneration: Long
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
        // Generation-checked like the stop below it: the registration above awaited GMS with no
        // lock held, so a sign-out can have completed inside that gap and cleared this state.
        val recorded = store.saveLastMovementTriggerLocationIfCurrent(
            GeofenceLocation(location.latitude, location.longitude),
            safeRadius,
            expectedUserStateGeneration
        )
        if (!recorded) return null
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
        val rebooted = store.getLastRegistrationUptime()?.let { SystemClock.elapsedRealtime() < it } == true
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
