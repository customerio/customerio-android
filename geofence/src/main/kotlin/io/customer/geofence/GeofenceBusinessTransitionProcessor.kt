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
        requireRegistered: Boolean = false
    ) = transitionMutex.withLock {
        // Capture admission before consulting routability. A user switch clears routability and
        // increments this generation; whichever side of that boundary this callback observes, it
        // cannot be attributed to the next identified user.
        val userStateGeneration = expectedUserStateGeneration ?: store.userStateGeneration()
        if (store.userStateGeneration() != userStateGeneration) return@withLock
        if (requireRegistered && geofenceId !in store.getRoutableRegisteredIds()) return@withLock
        val cachedRegion = store.getCachedRegion(geofenceId)
        val currentRegionRevision = cachedRegion?.transitionRevision()
        if (expectedRegionRevision != null && currentRegionRevision != expectedRegionRevision) {
            return@withLock
        }
        // Both clauses answer "should this EXIT exist", from different records, and a fence that
        // never reports ENTER is excluded from both: it can satisfy neither, so requiring either
        // would swallow every EXIT it produces. Mirrors isRedundantEnter in the emitter.
        val monitorsEnter = cachedRegion?.transitionTypes?.contains(GeofenceTransitionType.ENTER) == true
        val exitingUserId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
        // Where the device IS: nothing recorded us inside, so there is nothing to leave.
        val deviceWasNeverInside = geofenceId !in store.getEnteredIds() && store.hasContainmentRecord()
        // What the BACKEND believes: a sync can seed containment from a fix too coarse to
        // synthesise the matching ENTER, and the EXIT then reads as matched while the backend was
        // never told of an arrival. Measured on 2026-09-19: a 1 km circle registered around a
        // device already inside it, on a 400 m fix, delivered an EXIT for a visit never reported.
        val backendWasNeverTold = exitingUserId != null &&
            store.hasEmittedEnterRecord(exitingUserId) &&
            !store.hasEmittedEnter(exitingUserId, geofenceId)
        val isUnmatchedExit = transition == Event.GeofenceTransition.EXIT &&
            monitorsEnter &&
            (deviceWasNeverInside || backendWasNeverTold)
        if (isUnmatchedExit) {
            logger.logExitDroppedNeverEntered(geofenceId)
        }

        val configuredTransition = when (transition) {
            Event.GeofenceTransition.ENTER -> GeofenceTransitionType.ENTER
            Event.GeofenceTransition.EXIT -> GeofenceTransitionType.EXIT
        }
        // Suppresses delivery only. The departure itself is not in question, so it falls through to
        // the containment commit every other suppressed path reaches: returning here instead left
        // the fence in getEnteredIds() after the device had gone, and the redundant-ENTER guard
        // then read every later visit at that venue as unchanged. What the backend cannot be told
        // about is this one crossing, not the device's position.
        val shouldEmit = !isUnmatchedExit &&
            (
                !enforceConfiguredTransition ||
                    cachedRegion?.transitionTypes?.contains(configuredTransition) == true
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
                return@withLock
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
                    expectedRegionRevision = expectedRegionRevision ?: currentRegionRevision
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
                store.commitBusinessTransition(
                    geofenceId = geofenceId,
                    transition = transition,
                    transitionId = transitionId,
                    expectedUserStateGeneration = userStateGeneration,
                    expectedRegionRevision = expectedRegionRevision ?: currentRegionRevision
                )
                return@withLock
            }
            GeofenceTransitionEmitter.Result.PERSIST_FAILED -> {
                // A successful stage already committed physical containment atomically. If staging
                // itself failed, neither state nor event is durable and a later fine fix can retry.
                return@withLock
            }
            GeofenceTransitionEmitter.Result.SUPPRESSED,
            null -> Unit
        }
        store.commitBusinessTransition(
            geofenceId = geofenceId,
            transition = transition,
            transitionId = null,
            expectedUserStateGeneration = userStateGeneration,
            expectedRegionRevision = expectedRegionRevision ?: currentRegionRevision
        )
    }

    private companion object {
        val transitionMutex = Mutex()
    }
}
