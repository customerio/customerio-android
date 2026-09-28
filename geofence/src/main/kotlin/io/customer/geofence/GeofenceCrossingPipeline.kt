package io.customer.geofence

import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import kotlinx.coroutines.Job

/**
 * Ordering and admission decisions for a parsed [GeofenceCrossing], between the OS callback and
 * [GeofenceBusinessTransitionProcessor] (circle delivery) or [PolygonGeofenceServiceController]
 * (polygon verdicts).
 */
internal class GeofenceCrossingPipeline(
    private val regionStore: GeofenceRegionStore,
    private val services: GeofenceServices,
    private val registrar: GeofenceRegistrar,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor,
    private val polygonController: PolygonGeofenceServiceController,
    private val logger: GeofenceLogger
) {
    /** @return the movement-refresh [Job] this crossing started, so an OS execution window can wait for it. */
    suspend fun handle(crossing: GeofenceCrossing): Job? {
        // The receiver's stamp, not a fresh read: resolving this singleton on a cold process builds
        // the geofence graph first.
        val timestamp = crossing.receivedAtSeconds

        // Cold-start callbacks can beat the posted launch initialization. Establish the persisted
        // secure user's session synchronously; legacy installs without an owner are migrated by the
        // store without discarding their already-live OS registrations.
        polygonController.beginUserSessionForCurrentUser()
        // A previous callback can have staged a transition before the file outbox was writable.
        // Recover it before interpreting this edge so an ENTER followed by EXIT stays ordered.
        transitionProcessor.recoverPendingTransitions()

        val userStateGeneration = regionStore.userStateGeneration()
        val routableIds = regionStore.getRoutableRegisteredIds()
        val (routableTriggeringIds, unroutableIds) = crossing.geofenceIds.partition { it in routableIds }
        // An identify clears routing until its refresh completes, and a just-added fence can report
        // its INITIAL_TRIGGER_ENTER before routing arms. Such a registration is unroutable but not an
        // orphan: drop its callback, and evict only ids the bookkeeping no longer claims.
        val unarmedTriggerIds = if (unroutableIds.isEmpty()) {
            emptyList()
        } else {
            val registeredIds = regionStore.getRegisteredIds()
            val (unarmedIds, orphanIds) = unroutableIds.partition { it in registeredIds }
            orphanIds.forEach { logger.logTransitionDroppedUnknownId(it) }
            if (orphanIds.isNotEmpty()) {
                // Result ignored — a failed removal self-heals on the next orphan event.
                registrar.removeGeofencesByIds(orphanIds)
            }
            // The movement trigger has no business meaning and is the only refresh path that runs
            // without the app opening, so its EXIT is routed anyway to re-arm an unarmed session.
            val (triggerIds, businessIds) = unarmedIds.partition { it == GeofenceConstants.MOVEMENT_TRIGGER_ID }
            businessIds.forEach { logger.logTransitionDroppedUnarmedId(it) }
            triggerIds
        }

        // Circles, then the movement trigger, then polygons (stable, so GMS order holds per group).
        // Circles first: the refresh this batch starts can evict one, and `requireRegistered` would
        // then drop its delivered EXIT. The trigger before polygons: their handlers await GMS and
        // would spend the dispatch budget before the refresh job exists.
        val polygonIds = regionStore.getCachedRegions()
            .filter(GeofenceRegion::isPolygon)
            .mapTo(mutableSetOf(), GeofenceRegion::id)
        val knownIds = (routableTriggeringIds + unarmedTriggerIds).sortedBy { id ->
            when {
                id == GeofenceConstants.MOVEMENT_TRIGGER_ID -> 1
                id in polygonIds -> 2
                else -> 0
            }
        }

        var movementRefreshJob: Job? = null
        knownIds.forEach { geofenceId ->
            if (geofenceId == GeofenceConstants.MOVEMENT_TRIGGER_ID) {
                movementRefreshJob = handleMovementTrigger(crossing, userStateGeneration) ?: movementRefreshJob
                return@forEach
            }

            val region = regionStore.getCachedRegion(geofenceId)
            if (region == null && regionStore.getRegisteredRegion(geofenceId) != null) {
                // Server removed this region but its GMS removal failed. The retained definition is
                // a routing tombstone only: emit nothing, and retry OS cleanup.
                logger.logTransitionDroppedRetiredId(geofenceId)
                registrar.removeGeofencesByIds(listOf(geofenceId))
                return@forEach
            }

            if (region?.isPolygon == true) {
                when (crossing.transition) {
                    GeofenceCrossingTransition.ENTER -> polygonController.activate(
                        polygonId = geofenceId,
                        triggeringLocation = crossing.triggeringLocation,
                        expectedUserStateGeneration = userStateGeneration,
                        expectedRegionRevision = region.transitionRevision()
                    )
                    GeofenceCrossingTransition.EXIT -> polygonController.onCoarseExit(
                        polygonId = geofenceId,
                        triggeringLocation = crossing.triggeringLocation,
                        expectedUserStateGeneration = userStateGeneration,
                        expectedRegionRevision = region.transitionRevision()
                    )
                    GeofenceCrossingTransition.UNSUPPORTED ->
                        logger.logUnknownTransition(geofenceId, crossing.rawTransitionCode)
                }
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

            transitionProcessor.process(
                geofenceId = geofenceId,
                transition = transition,
                timestampSeconds = timestamp,
                enforceConfiguredTransition = region != null,
                expectedRegionRevision = region?.transitionRevision(),
                expectedUserStateGeneration = userStateGeneration,
                requireRegistered = true
            )
        }
        return movementRefreshJob
    }

    /** Only EXIT drives a refresh; the trigger is registered for EXIT alone. */
    private fun handleMovementTrigger(crossing: GeofenceCrossing, userStateGeneration: Long): Job? {
        if (crossing.transition != GeofenceCrossingTransition.EXIT) {
            logger.logMovementTriggerIgnoredNonExit(crossing.transitionName)
            return null
        }
        // Passed as a lambda: the polygon radius callback awaits GMS, and running it here would
        // spend the broadcast budget before the refresh job exists.
        return services.onMovementTriggerExit(
            latitude = crossing.latitude,
            longitude = crossing.longitude,
            movementTriggerRadius = {
                polygonController.onMovementTriggerExit(
                    triggeringLocation = crossing.triggeringLocation,
                    expectedUserStateGeneration = userStateGeneration
                )
            }
        )
    }
}
