package io.customer.geofence

import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Single authority for durable containment and transition delivery for every geofence shape. */
internal class GeofenceBusinessTransitionProcessor(
    private val store: GeofenceRegionStore,
    private val secureUserStore: SecureUserStore,
    private val transitionEmitter: GeofenceTransitionEmitter,
    private val logger: GeofenceLogger
) {
    suspend fun recoverPendingTransitions(): Boolean = transitionMutex.withLock {
        transitionEmitter.recoverPendingTransitions()
    }

    suspend fun process(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        timestampSeconds: Long,
        enforceConfiguredTransition: Boolean = false,
        expectedRegionRevision: Int? = null,
        expectedUserStateGeneration: Long? = null,
        requireRegistered: Boolean = false,
        visitContext: GeofenceTransitionEmitter.VisitContext? = null,
        // False when the caller already ended the visit this EXIT provably belongs to. A native
        // callback's receipt time cannot order it against a newer visit, so ending by timestamp
        // here could clear that newer visit.
        endsVisitByTimestamp: Boolean = true
    ): GeofenceTransitionEmitter.Result? = transitionMutex.withLock {
        // Read the generation before routability: a user switch clears one and bumps the other, so
        // this callback can never be attributed to the next identified user.
        val userStateGeneration = expectedUserStateGeneration ?: store.userStateGeneration()
        if (store.userStateGeneration() != userStateGeneration) return@withLock null
        val cachedRegion = store.getCachedRegion(geofenceId)
        val currentRegionRevision = cachedRegion?.transitionRevision()
        if (expectedRegionRevision != null && currentRegionRevision != expectedRegionRevision) {
            return@withLock null
        }
        if (requireRegistered && geofenceId !in store.getRoutableRegisteredIds()) {
            // The callback cannot be delivered for a region outside the current routable set, but
            // a physical EXIT still ends continuous presence. Commit that containment change and
            // clear only the visit this callback can own, so a later ENTER starts a fresh visit
            // instead of inheriting time spent away from the fence.
            if (transition == Event.GeofenceTransition.EXIT) {
                if (geofenceId in store.getEnteredIds()) {
                    store.commitBusinessTransition(
                        geofenceId = geofenceId,
                        transition = transition,
                        transitionId = null,
                        expectedUserStateGeneration = userStateGeneration,
                        expectedRegionRevision = expectedRegionRevision ?: currentRegionRevision
                    )
                }
                // Even when containment has no departure to commit, an EXIT can close a stranded
                // visit left by a missed edge. The timestamp/revision/generation checks in the
                // store keep a delayed callback from clearing a newer visit.
                removeCompletedVisit(
                    endsVisitByTimestamp,
                    geofenceId,
                    transition,
                    timestampSeconds,
                    userStateGeneration,
                    expectedRegionRevision ?: currentRegionRevision
                )
            }
            return@withLock null
        }
        // A fence that never reports ENTER is exempt from both clauses below: it can satisfy
        // neither, so requiring either would swallow every EXIT it produces. Mirrors
        // isRedundantEnter in the emitter.
        val monitorsEnter = cachedRegion?.transitionTypes?.contains(GeofenceTransitionType.ENTER) == true
        val exitingUserId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
        val deviceWasNeverInside = geofenceId !in store.getEnteredIds() && store.hasContainmentRecord()
        // A sync can seed containment from a fix too coarse to synthesise the matching ENTER, so the
        // EXIT would read as matched for an arrival the backend was never told about.
        val backendWasNeverTold = exitingUserId != null &&
            store.hasEmittedEnterRecord(exitingUserId) &&
            !store.hasEmittedEnter(exitingUserId, geofenceId)
        val isUnmatchedExit = transition == Event.GeofenceTransition.EXIT &&
            monitorsEnter &&
            (deviceWasNeverInside || backendWasNeverTold)
        if (isUnmatchedExit) {
            logger.logExitDroppedNeverEntered(geofenceId)
        }
        // Only `deviceWasNeverInside` skips the commit: it has no departure, and committing one would
        // bump the exit epoch, making reconcileEnteredIds drop an in-flight sync's inside seed.
        if (transition == Event.GeofenceTransition.EXIT && monitorsEnter && deviceWasNeverInside) {
            return@withLock null
        }

        val configuredTransition = when (transition) {
            Event.GeofenceTransition.ENTER -> GeofenceTransitionType.ENTER
            Event.GeofenceTransition.DWELL -> null
            Event.GeofenceTransition.EXIT -> GeofenceTransitionType.EXIT
        }
        // Suppresses delivery only; the containment commit below must still run, or the fence stays in
        // getEnteredIds() and the redundant-ENTER guard reads every later visit as unchanged.
        val shouldEmit = !isUnmatchedExit &&
            (
                !enforceConfiguredTransition ||
                    if (transition == Event.GeofenceTransition.DWELL) {
                        cachedRegion?.dwellThresholdSeconds?.let { it > 0 } == true
                    } else {
                        configuredTransition != null && cachedRegion?.transitionTypes?.contains(configuredTransition) == true
                    }
                )
        var emissionResult: GeofenceTransitionEmitter.Result? = null
        var emittingUserId: String? = null
        if (shouldEmit) {
            val userId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
            if (userId == null) {
                logger.logTransitionDroppedAnonymous(geofenceId, transition.name)
            } else if (
                store.userStateGeneration() != userStateGeneration ||
                store.activeUserSessionId() != userId
            ) {
                return@withLock null
            } else {
                // Synchronously stage the attempt, then append the file outbox before committing
                // containment. Recovery reuses the staged transition ID across every crash window.
                emittingUserId = userId
                emissionResult = transitionEmitter.emitWithRetainedAttempt(
                    geofenceId = geofenceId,
                    transition = transition,
                    userId = userId,
                    timestampSeconds = timestampSeconds,
                    geofenceName = cachedRegion?.name,
                    metadata = cachedRegion?.metadata ?: emptyMap(),
                    geosetIds = cachedRegion?.geosetIds ?: emptyList(),
                    monitorsExit = cachedRegion?.transitionTypes?.contains(GeofenceTransitionType.EXIT) == true,
                    expectedUserStateGeneration = userStateGeneration,
                    expectedRegionRevision = expectedRegionRevision ?: currentRegionRevision,
                    visitContext = visitContext
                )
            }
        }

        val transitionId = emittingUserId?.let { userId ->
            store.getPendingTransitionEntries(userId, geofenceId, transition)
                .lastOrNull()
                ?.transitionId
        }
        when (emissionResult) {
            GeofenceTransitionEmitter.Result.PERSISTED -> {
                val committed = store.commitBusinessTransition(
                    geofenceId = geofenceId,
                    transition = transition,
                    transitionId = transitionId,
                    expectedUserStateGeneration = userStateGeneration,
                    expectedRegionRevision = expectedRegionRevision ?: currentRegionRevision
                )
                if (committed) {
                    removeCompletedVisit(
                        endsVisitByTimestamp,
                        geofenceId,
                        transition,
                        timestampSeconds,
                        userStateGeneration,
                        expectedRegionRevision ?: currentRegionRevision
                    )
                }
                return@withLock emissionResult
            }
            GeofenceTransitionEmitter.Result.PERSIST_FAILED -> {
                // A successful stage already committed physical containment atomically. If staging
                // itself failed, neither state nor event is durable and a later fine fix can retry.
                if (transition == Event.GeofenceTransition.EXIT && geofenceId !in store.getEnteredIds()) {
                    removeCompletedVisit(
                        endsVisitByTimestamp,
                        geofenceId,
                        transition,
                        timestampSeconds,
                        userStateGeneration,
                        expectedRegionRevision ?: currentRegionRevision
                    )
                }
                return@withLock emissionResult
            }
            GeofenceTransitionEmitter.Result.SUPPRESSED,
            null -> Unit
        }
        val committed = store.commitBusinessTransition(
            geofenceId = geofenceId,
            transition = transition,
            transitionId = null,
            expectedUserStateGeneration = userStateGeneration,
            expectedRegionRevision = expectedRegionRevision ?: currentRegionRevision
        )
        if (committed) {
            removeCompletedVisit(
                endsVisitByTimestamp,
                geofenceId,
                transition,
                timestampSeconds,
                userStateGeneration,
                expectedRegionRevision ?: currentRegionRevision
            )
        }
        emissionResult
    }

    private fun removeCompletedVisit(
        endsVisitByTimestamp: Boolean,
        geofenceId: String,
        transition: Event.GeofenceTransition,
        timestampSeconds: Long,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int?
    ) {
        if (!endsVisitByTimestamp || transition != Event.GeofenceTransition.EXIT) return
        store.removeDwellVisitAfterCommittedExit(
            geofenceId = geofenceId,
            exitedAtSeconds = timestampSeconds,
            expectedUserStateGeneration = expectedUserStateGeneration,
            expectedRegionRevision = expectedRegionRevision
        )
    }

    private companion object {
        val transitionMutex = Mutex()
    }
}
