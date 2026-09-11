package io.customer.geofence

import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything the SDK decides about a crossing, with no OS in sight.
 *
 * This used to live inside `GeofenceBroadcastReceiver`. A `BroadcastReceiver` is an OS entry point:
 * it cannot be constructed with dependencies, it reads the world through a service locator, and the
 * only way a test could reach these decisions was a `@VisibleForTesting` method that still took a
 * raw GMS transition code. Substituting the OS therefore also substituted the orphan drop, the
 * containment guard, the anonymous drop and the movement routing — the four things most worth
 * asserting.
 *
 * The split follows the Humble Object pattern: the line a test substitutes along and the line where
 * the OS actually starts are now the same line. The receiver keeps what genuinely needs an OS —
 * parsing the intent, holding the `goAsync` window open — and hands over a [GeofenceCrossing].
 *
 * Five of the SDK's six asserted records are produced here or downstream of here.
 */
internal class GeofenceCrossingPipeline(
    private val regionStore: GeofenceRegionStore,
    private val secureUserStore: SecureUserStore,
    private val transitionEmitter: GeofenceTransitionEmitter,
    private val services: GeofenceServices,
    private val registrar: GeofenceRegistrar,
    private val clock: Clock,
    private val logger: GeofenceLogger
) {
    /**
     * Serializes the read-decide-persist sequence in [handleBusinessTransition].
     *
     * Each broadcast runs on its own scope, so two transitions for one fence would otherwise
     * interleave and lose a containment record. An instance field rather than a companion object
     * because this class is a DI singleton — process-wide either way, but this way the scope is
     * the object's, not the classloader's, and a test gets a fresh one per composition.
     */
    private val transitionMutex = Mutex()

    /**
     * Routes one crossing.
     *
     * @return the movement-trigger refresh [Job] when this crossing started one, so the caller
     * holding an OS execution window can wait for it. Null when no refresh was launched.
     */
    suspend fun handle(crossing: GeofenceCrossing): Job? {
        val timestamp = clock.currentTimeSeconds()

        // Defense-in-depth against orphans (failed clearAll, app-data wipe, SDK ID-format changes):
        // events for unregistered IDs are dropped and the OS-side registration is removed so it
        // stops firing.
        val registeredIds = regionStore.getRegisteredIds()
        val (knownIds, unknownIds) = crossing.geofenceIds.partition { it in registeredIds }
        if (unknownIds.isNotEmpty()) {
            unknownIds.forEach { logger.logTransitionDroppedUnknownId(it) }
            // Result ignored — a failed removal self-heals on the next orphan event.
            registrar.removeGeofencesByIds(unknownIds)
        }

        var movementRefreshJob: Job? = null
        knownIds.forEach { geofenceId ->
            if (geofenceId == GeofenceConstants.MOVEMENT_TRIGGER_ID) {
                movementRefreshJob = handleMovementTrigger(crossing) ?: movementRefreshJob
                return@forEach
            }

            val transition = when (crossing.transition) {
                GeofenceCrossingTransition.ENTER -> Event.GeofenceTransition.ENTER
                GeofenceCrossingTransition.EXIT -> Event.GeofenceTransition.EXIT
                GeofenceCrossingTransition.UNSUPPORTED -> {
                    logger.logUnknownTransition(geofenceId, crossing.rawTransitionCode)
                    return@forEach
                }
            }

            transitionMutex.withLock {
                handleBusinessTransition(
                    geofenceId = geofenceId,
                    transition = transition,
                    timestamp = timestamp
                )
            }
        }
        return movementRefreshJob
    }

    /**
     * ENTER fires on every re-registration and boot-restore can fire EXIT. Only EXIT drives a
     * refresh.
     */
    private fun handleMovementTrigger(crossing: GeofenceCrossing): Job? {
        if (crossing.transition != GeofenceCrossingTransition.EXIT) {
            logger.logMovementTriggerIgnoredNonExit(crossing.transitionName)
            return null
        }
        return services.onMovementTriggerExit(crossing.latitude, crossing.longitude)
    }

    private suspend fun handleBusinessTransition(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        timestamp: Long
    ) {
        // Ahead of the identity checks below: being inside a fence is a physical fact, independent
        // of whether the transition is deliverable.
        val cachedRegion = regionStore.getCachedRegion(geofenceId)
        when (transition) {
            Event.GeofenceTransition.ENTER -> regionStore.recordEntered(geofenceId)
            // An unmatched EXIT is a GMS reconciliation artifact, not a crossing — delivering it
            // fires EXIT campaigns at people who were never there. The two guards after claimExit
            // cover the cases where an absent record proves nothing: an upgraded install with no
            // set yet, and a region that never monitored ENTER (a missing cache row counts as
            // unknown).
            Event.GeofenceTransition.EXIT -> if (
                !regionStore.claimExit(geofenceId) &&
                regionStore.hasContainmentRecord() &&
                cachedRegion?.transitionTypes?.contains(GeofenceTransitionType.ENTER) == true
            ) {
                logger.logExitDroppedNeverEntered(geofenceId)
                return
            }
        }

        // Snapshot userId so a sign-out + sign-in before delivery can't reattribute this
        // transition. Empty userId is treated as "not identified" per `isUserIdentified`.
        val userId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
        // Identified-only: the backend rejects anonymous geofence tracks, so drop before spending a
        // cooldown slot or persisting a row neither channel could send.
        if (userId == null) {
            logger.logTransitionDroppedAnonymous(geofenceId, transition.name)
            return
        }

        transitionEmitter.emit(
            geofenceId = geofenceId,
            transition = transition,
            userId = userId,
            timestampSeconds = timestamp,
            geofenceName = cachedRegion?.name,
            metadata = cachedRegion?.metadata ?: emptyMap(),
            geosetIds = cachedRegion?.geosetIds ?: emptyList(),
            monitorsExit = cachedRegion?.transitionTypes?.contains(GeofenceTransitionType.EXIT) == true
        )
    }
}
