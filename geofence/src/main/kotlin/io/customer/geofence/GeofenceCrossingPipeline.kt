package io.customer.geofence

import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything the SDK decides about a crossing, with no OS in sight. The receiver parses the
 * intent and hands over a [GeofenceCrossing].
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
     * Serializes read-decide-persist per fence. This class is a DI singleton, so the lock is
     * process-wide, which two concurrent broadcasts for one fence require.
     */
    private val transitionMutex = Mutex()

    /** @return the movement-refresh [Job] this crossing started, so an OS execution window can wait for it. */
    suspend fun handle(crossing: GeofenceCrossing): Job? {
        val timestamp = clock.currentTimeSeconds()

        // Unregistered ids are orphans: drop them and remove the OS registration so they stop firing.
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

    /** Only EXIT drives a refresh: ENTER fires on every re-registration and boot-restore can fire EXIT. */
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
        // Containment is recorded before the identity checks: being inside is a fact either way.
        val cachedRegion = regionStore.getCachedRegion(geofenceId)
        when (transition) {
            Event.GeofenceTransition.ENTER -> regionStore.recordEntered(geofenceId)
            // An unmatched EXIT is GMS reconciliation, not a crossing. The two guards after claimExit
            // cover the cases where an absent record proves nothing: no containment set yet, or ENTER never monitored.
            Event.GeofenceTransition.EXIT -> if (
                !regionStore.claimExit(geofenceId) &&
                regionStore.hasContainmentRecord() &&
                cachedRegion?.transitionTypes?.contains(GeofenceTransitionType.ENTER) == true
            ) {
                logger.logExitDroppedNeverEntered(geofenceId)
                return
            }
        }

        // Snapshot userId now so a sign-out + sign-in before delivery cannot reattribute this transition.
        val userId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
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
