package io.customer.geofence

import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import kotlinx.coroutines.Job

/**
 * Everything the SDK decides about a crossing, with no intent parsing in sight. The receiver
 * parses the broadcast and hands over a [GeofenceCrossing].
 *
 * Business delivery lives in [GeofenceBusinessTransitionProcessor] and polygon verdicts in
 * [PolygonGeofenceServiceController]; this type owns only the ordering and admission decisions
 * that sit between the OS callback and those two.
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
        // The receiver's stamp, not a fresh read: this runs after the caller resolved this
        // singleton, which on a cold process builds the geofence graph including the GMS client.
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
        // An identify clears routing and only the completing refresh re-arms it, so between the two
        // a live registration is unroutable without being an orphan. Removing it there strands it:
        // business fences register with INITIAL_TRIGGER_ENTER and routing arms only after the add,
        // so a fence the refresh just added can report ENTER while routing still reads empty. Only
        // evict what the bookkeeping no longer claims, and drop the rest.
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
            // The movement trigger is SDK-owned and carries no business meaning — routing it only
            // re-fetches. It is also the only refresh path that runs without the app being opened,
            // so dropping its EXIT here would leave an unarmed session with nothing to re-arm it.
            val (triggerIds, businessIds) = unarmedIds.partition { it == GeofenceConstants.MOVEMENT_TRIGGER_ID }
            businessIds.forEach { logger.logTransitionDroppedUnarmedId(it) }
            triggerIds
        }

        // Circles, then the movement trigger, then polygons, whatever order GMS delivered.
        //
        // Circles first because the refresh this batch starts can evict one under the monitoring
        // cap, and its `requireRegistered` check would then drop an EXIT the OS had already
        // delivered. The trigger next because the polygon handlers await GMS, so leaving it behind
        // them spends the dispatch budget before the refresh job exists. Sorting is stable, so GMS
        // order survives within each group.
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
                // The server removed this region, but an earlier GMS removal failed. Its retained
                // definition is a routing tombstone only; never manufacture business activity for a
                // fence that no longer exists. Every orphan callback also retries OS cleanup.
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

    /** Only EXIT drives a refresh: ENTER fires on every re-registration and boot-restore can fire EXIT. */
    private fun handleMovementTrigger(crossing: GeofenceCrossing, userStateGeneration: Long): Job? {
        if (crossing.transition != GeofenceCrossingTransition.EXIT) {
            logger.logMovementTriggerIgnoredNonExit(crossing.transitionName)
            return null
        }
        // Handed over unevaluated. The polygon radius callback awaits GMS, and doing that here
        // would spend the broadcast budget before the refresh job exists.
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
