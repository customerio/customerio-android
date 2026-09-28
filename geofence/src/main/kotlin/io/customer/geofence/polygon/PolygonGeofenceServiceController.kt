package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
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
    private val manager: GeofenceRegistrar,
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
     * Bumped by every teardown, so a scheduled wake already past its wait cannot re-arm what the
     * teardown removed. Teardown leaves the routable ids and user generation in place, so those
     * checks alone cannot tell such a wake its session is over.
     */
    private var teardownGeneration: Long = 0L

    /** Captured by a scheduled wake before it waits, and handed back to [activate] afterwards. */
    fun teardownGeneration(): Long = synchronized(controllerLock) { teardownGeneration }

    private fun isAfterTeardown(expected: Long?): Boolean = expected != null &&
        synchronized(controllerLock) { isAfterTeardownLocked(expected) }

    /**
     * For callers inside [controllerLock], where every teardown bumps the generation, so no
     * teardown can land between this check and the state it guards. The unlocked checks made
     * earlier are only a fast path.
     */
    private fun isAfterTeardownLocked(expected: Long?): Boolean =
        expected != null && expected != teardownGeneration

    /**
     * Called by every teardown. The fix memos are not keyed by user, but a previous session's fix
     * must not decide the next user's arrival, and its rate limit would refuse the next user's
     * first callback its own fix.
     */
    private fun forgetRequestedFix() {
        lastFreshFix = null
        lastFreshFixRequestElapsedMs = null
        teardownGeneration += 1
        futileEscalations.clear()
    }

    /**
     * A precise fix that was requested for a polygon and still could not decide it. Guarded by
     * [controllerLock] and cleared by [forgetRequestedFix].
     */
    private data class FutileEscalation(
        val fix: Location,
        val atElapsedMs: Long
    )

    private val futileEscalations = mutableMapOf<String, FutileEscalation>()
    private val coarseTransitionMutex = Mutex()
    private val lastCoarseTransitionElapsedNanos = mutableMapOf<String, Long>()

    /**
     * Reached on every coarse ENTER, including those GMS re-reports after an identify re-arms
     * routing. Not suppressed for that case: the identify cleared containment, and unlike a circle
     * a polygon cannot recover it at registration because the sync fix carries no accuracy, so a
     * user already inside would get no ENTER until they move.
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
        // Passed on so the locked activation re-checks it: the lock is not held across the checks
        // above, so a teardown can land after them.
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
     * arrival holds after leaving it, so no record reaches the host log dispatcher under the lock.
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
     * Takes a teardown token like [activate] because its tail can re-arm: a polygon still in the
     * entered set, which teardown retains, is re-activated. Without the token, a departure that
     * runs after a teardown would restart approach sampling.
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
            // Also checked at the tail, which guards the re-arm. This one guards the write below
            // and the fix evaluation, which can open the GPS for a session already over.
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
            // Separate reasons: "no polygon in range" is routine, while a session mismatch here
            // can lose an arrival and must not read as routine.
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
        // Outside the lock: WorkManager does disk work. Keyed on registered ids, not active ones:
        // the re-check exists for a polygon that never woke, and so is not active.
        if (ids.isEmpty()) {
            stopScheduledWakes()
        } else {
            // The passive listener is free to hold but fires only when another app asks for
            // location, so it complements the re-check rather than replacing it.
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
                // No accuracy margin: the backend circle is only tens of metres wider than the
                // ring, so a coarse fix could never fit its whole accuracy circle inside. A false
                // admission costs one undecided evaluation; a missed one loses the visit, since
                // nothing re-derives a polygon arrival.
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
            // Logged outside the lock: the host log dispatcher is customer code.
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
        // Locked and generation-checked: the trigger update above awaits GMS, and an identify in
        // that gap would otherwise answer CONTINUE for an ended generation, replacing the approach
        // request the new user just armed.
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
         * How long a dispatch may wait for a precise fix. It spends `GeofenceBroadcastReceiver`'s
         * 8 s `DISPATCH_WAIT_BUDGET_MS` ahead of the movement-refresh join, so it stays short
         * enough to leave that join most of its window. The cooldown limits this to one wait per
         * dispatch, since a timeout still leaves the rate limit armed.
         */
        const val FRESH_FIX_TIMEOUT_MS = 3_000L

        /** @see preciseFixForCallback */
        const val FRESH_FIX_COOLDOWN_MS = 30_000L

        /**
         * How long a futile escalation suppresses the next one for the same polygon from the same
         * position. Caps a parked device at 48 requests a day; a device that moves is never
         * delayed because the position check lets it through.
         */
        const val FUTILE_ESCALATION_RETRY_MS = 30 * 60_000L

        /**
         * How long a fix already obtained may answer a later callback from the same GMS batch.
         * A batch's fences are dispatched back to back within one broadcast, so a short window
         * covers it. Kept short because a reused fix bypasses the cooldown and the engine accepts
         * fixes up to 120 s old, so a longer window could decide from a position already left.
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
         * [requestSessionElapsedMs] is the token of the request that produced [fix], read under
         * the same lock as the reuse decision, so recording from a reused fix can still be checked
         * against a teardown.
         */
        data class Reuse(val fix: Location, val requestSessionElapsedMs: Long?) : CallbackFixDecision
        data object Request : CallbackFixDecision
        data object WithinCooldown : CallbackFixDecision
        data object UnchangedPosition : CallbackFixDecision
    }

    /**
     * Stops both schedule-driven wakes, outside [controllerLock] because WorkManager does disk work
     * and both log through the host dispatcher.
     *
     * Teardowns call this before their state wipe, and the order matters. Cancelling the passive
     * intent first makes [PolygonPassiveMonitor.isArmed] refuse every later delivery, whatever
     * token it captured, and one admitted just before the cancel is then cleared by the wipe.
     *
     * Unconditional: [stopAll] and [clearUserSessionRetainingOsRegistrations] keep registrations
     * on purpose, so keying on them would re-arm the wake being removed.
     * [reconcileRegisteredPolygons] arms it again on the next registration write.
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
     * Reports arrival holds that a teardown discarded. Called after [controllerLock] is released,
     * because [GeofenceLogger] forwards to the host `Logger`, whose dispatcher is customer code.
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
     * nothing or left an arrival held.
     *
     * The trigger is undecided, not stale: the callback's fix is the one GMS computed for the
     * crossing, but GMS picks its accuracy. This request is the only fix whose accuracy the SDK
     * chooses. If it returns nothing, the delivered verdict stands.
     */
    private suspend fun evaluateCallbackFix(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long
    ) {
        if (triggeringLocation == null) {
            // No attached fix, so the precise fix is the only evidence.
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
        // A held arrival needs a second measurement, and the same request serves it. GMS often
        // answers with this callback's own fix; answersHeldFixAt lets the engine settle the hold
        // as not independent instead of skipping that fix as not newer.
        val needing = delivered.undecidedPolygonIds + delivered.pendingArrivalPolygonIds
        if (needing.isEmpty()) return
        val fresh = preciseFixForCallback(
            polygonId,
            delivered.undecidedPolygonIds,
            delivered.pendingArrivalPolygonIds,
            triggeringLocation
        ) ?: return
        val after = evaluateAndRecentre(
            fresh.fix,
            expectedUserStateGeneration,
            answersHeldFixAt = triggeringLocation.elapsedRealtimeNanos
        )
        // An aborted pass reports nothing undecided without deciding anything, so it must not
        // clear the memos.
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
        expectedUserStateGeneration: Long,
        answersHeldFixAt: Long? = null
    ): PolygonEvaluationOutcome {
        val outcome = processTriggeredLocation(fix, expectedUserStateGeneration, answersHeldFixAt)
        if (outcome.acceptedFix) {
            updateMovementTriggerFromAcceptedFix(fix, expectedUserStateGeneration)
        }
        return outcome
    }

    /**
     * A precise fix for this callback, or null to evaluate only what it delivered.
     *
     * Rate-limited because one GMS batch can report several polygons, each dispatched separately.
     * Within the cooldown a young fix already obtained answers later callbacks, so a batch costs
     * one request.
     */
    private suspend fun preciseFixForCallback(
        polygonId: String,
        undecidedPolygonIds: Set<String>,
        pendingArrivalPolygonIds: Set<String>,
        triggeringLocation: Location?
    ): CallbackFix? {
        val needingPolygonIds = undecidedPolygonIds + pendingArrivalPolygonIds
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
                // Ahead of the unchanged-position check, so a rate-limited skip is logged as such.
                previous != null && requestedAt - previous < FRESH_FIX_COOLDOWN_MS ->
                    CallbackFixDecision.WithinCooldown
                requestWouldRepeatItselfLocked(
                    needingPolygonIds,
                    pendingArrivalPolygonIds,
                    triggeringLocation,
                    requestedAt
                ) -> CallbackFixDecision.UnchangedPosition
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
            // Memoised against the delivered fix, or a device whose precise fix never arrives
            // would ask again on every wake.
            //
            // Held arrivals are left out: a hold is not futile, and a memo would withhold the next
            // request for up to FUTILE_ESCALATION_RETRY_MS, far longer than the hold survives
            // (MAX_CORROBORATION_GAP_NANOS), losing the arrival.
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
        // Memoised only if this request is still the one in flight: a reset during the await
        // cleared the memo, and storing now would offer the previous user's fix for reuse. This
        // callback still gets the fix; its own generation check guards the verdict.
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
     * Whether a request for [needingPolygonIds] would repeat one that already failed for every
     * fence in it.
     *
     * Checks the whole set the request serves, not just the callback's own fence, and suppresses
     * only when all are memoised: one fence the device has moved relative to is reason enough to
     * spend the fix. An empty set never suppresses.
     *
     * An open hold bypasses the check. A memo means a fix from here could not decide the fence,
     * but a repeat of the same position does settle a held arrival, and without the fix the hold
     * goes stale and the visit is lost.
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
     * Whether asking for another precise fix would repeat one that already failed. The cooldown is
     * far shorter than the wake cadence, so without this a parked device asks on every wake.
     *
     * The discriminator is movement, not time: a fix from where the last futile one was taken
     * cannot separate inside from outside any better. [FUTILE_ESCALATION_RETRY_MS] still lets one
     * through periodically, because a stationary device can still get a fix precise enough to
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
     * them, not just the callback's own id. This covers a polygon that [onMovementTriggerExit]
     * activated, which has no callback of its own to record it.
     */
    private fun recordEscalationOutcomes(
        requested: Set<String>,
        stillUndecided: Set<String>,
        evaluated: Set<String>,
        fix: Location,
        atElapsedMs: Long,
        requestSessionElapsedMs: Long?
    ) = synchronized(controllerLock) {
        // Recorded only while this request is still the one in flight: a teardown during
        // awaitFreshFix clears futileEscalations, and writing now would suppress the next
        // session's first precise fix here. Keyed on the request, not the store generation,
        // because a reset that clears this need not advance a generation. A reused fix carries its
        // producing request's token, so it is still recorded. A null token never vouches for one.
        if (requestSessionElapsedMs == null || lastFreshFixRequestElapsedMs != requestSessionElapsedMs) {
            return@synchronized
        }
        requested.forEach { id ->
            when {
                id in stillUndecided -> futileEscalations[id] = FutileEscalation(fix, atElapsedMs)
                // A fix that decided says this position is answerable after all.
                id in evaluated -> futileEscalations.remove(id)
                // Not judged: the route processor skipped a fix not newer than its last one for
                // this fence, so the pass says nothing about this position.
                else -> Unit
            }
        }
    }

    /** Null when the fix carries no monotonic timestamp, which is the same as carrying no age. */
    private fun Location.fixAgeMsOrNull(nowElapsedMs: Long): Long? =
        elapsedRealtimeNanos.takeIf { it > 0L }?.let { nowElapsedMs - it / NANOS_PER_MILLI }

    private suspend fun processTriggeredLocation(
        location: Location,
        expectedUserStateGeneration: Long,
        answersHeldFixAt: Long? = null
    ): PolygonEvaluationOutcome =
        engine.processResponsiveLocation(location, expectedUserStateGeneration, answersHeldFixAt)

    // replaceMovementTrigger checks the permission itself and fails when it is missing.
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
