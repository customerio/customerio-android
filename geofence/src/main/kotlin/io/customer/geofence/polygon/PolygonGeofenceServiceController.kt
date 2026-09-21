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

    private fun isAfterTeardown(expected: Long?): Boolean =
        synchronized(controllerLock) { isAfterTeardownLocked(expected) }

    /**
     * For callers already inside [controllerLock], so the check cannot be separated from the state
     * it guards. Every teardown bumps the generation under this same lock, which is what makes a
     * check made inside it one no teardown can land behind. The checks made before taking the lock
     * are worth keeping anyway: they stop a dead wake from churning the dedupe memo on its way to
     * being refused.
     */
    private fun isAfterTeardownLocked(expected: Long?): Boolean =
        expected != null && expected != teardownGeneration

    private fun forgetRequestedFix() {
        lastFreshFix = null
        lastFreshFixRequestElapsedMs = null
        teardownGeneration += 1
    }
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
            if (!isCurrentRegisteredPolygonLocked(
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
        if (ids.isEmpty()) stopScheduledWakes() else recheckScheduler.schedule()
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
    private sealed interface CallbackFixDecision {
        data class Reuse(val fix: Location) : CallbackFixDecision
        data object Request : CallbackFixDecision
        data object WithinCooldown : CallbackFixDecision
    }

    /**
     * Stops the schedule-driven wake. Every path that tears geofencing or the user session down
     * calls this, outside [controllerLock], because WorkManager does disk work and logs through the
     * host dispatcher.
     *
     * Unconditional rather than keyed on what is still registered: [stopAll] and
     * [clearUserSessionRetainingOsRegistrations] leave registrations in place on purpose, so
     * reading them here would re-arm the wake the caller is removing.
     * [reconcileRegisteredPolygons] arms it again the next time registrations are written.
     */
    private fun stopScheduledWakes() {
        recheckScheduler.cancel()
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
        stopScheduledWakes()
    }

    fun stopAll() {
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
        stopScheduledWakes()
    }

    fun clearUserScopedState() {
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
        stopScheduledWakes()
    }

    fun clearUserSessionRetainingOsRegistrations() {
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
        stopScheduledWakes()
    }

    fun completeUserReset(
        expectedUserStateGeneration: Long,
        osRegistrationsCleared: Boolean
    ) {
        val discarded = synchronized(controllerLock) {
            store.completeUserReset(expectedUserStateGeneration, osRegistrationsCleared)
            val holds = engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            forgetRequestedFix()
            approachMonitor.stop(expectedUserStateGeneration)
            holds
        }
        reportDiscardedArrivals(discarded)
        stopScheduledWakes()
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
            val only = preciseFixForCallback(polygonId, setOf(polygonId)) ?: return
            evaluateAndRecentre(only, expectedUserStateGeneration)
            return
        }
        val delivered = evaluateAndRecentre(triggeringLocation, expectedUserStateGeneration)
        if (delivered.undecidedPolygonIds.isEmpty()) return
        val fresh = preciseFixForCallback(polygonId, delivered.undecidedPolygonIds) ?: return
        evaluateAndRecentre(fresh, expectedUserStateGeneration)
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
        undecidedPolygonIds: Set<String>
    ): Location? {
        val requestedAt = SystemClock.elapsedRealtime()
        val decision = synchronized(controllerLock) {
            val reusable = lastFreshFix?.takeIf {
                (it.fixAgeMsOrNull(requestedAt) ?: Long.MAX_VALUE) <= BATCH_REUSE_WINDOW_MS
            }
            val previous = lastFreshFixRequestElapsedMs
            when {
                reusable != null -> CallbackFixDecision.Reuse(reusable)
                previous != null && requestedAt - previous < FRESH_FIX_COOLDOWN_MS ->
                    CallbackFixDecision.WithinCooldown
                else -> {
                    lastFreshFixRequestElapsedMs = requestedAt
                    CallbackFixDecision.Request
                }
            }
        }
        when (decision) {
            is CallbackFixDecision.Reuse -> return decision.fix
            CallbackFixDecision.WithinCooldown -> {
                logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.WITHIN_COOLDOWN)
                return null
            }
            CallbackFixDecision.Request -> Unit
        }
        logger.logPolygonFreshFixRequested(undecidedPolygonIds.sorted())
        val fix = freshFixSource.awaitFreshFix(FRESH_FIX_TIMEOUT_MS)
        if (fix == null) {
            logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.NONE_ARRIVED)
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
        return fix
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
