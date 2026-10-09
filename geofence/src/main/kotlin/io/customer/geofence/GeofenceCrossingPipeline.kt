package io.customer.geofence

import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.communication.Event
import kotlinx.coroutines.Job

/**
 * Ordering and admission for a [GeofenceCrossing] before [GeofenceBusinessTransitionProcessor]
 * (circles) or [PolygonGeofenceServiceController] (polygons).
 */
internal class GeofenceCrossingPipeline(
    private val regionStore: GeofenceRegionStore,
    private val services: GeofenceServices,
    private val registrar: GeofenceRegistrar,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor,
    private val dwellCoordinator: GeofenceDwellCoordinator,
    private val polygonController: PolygonGeofenceServiceController,
    private val logger: GeofenceLogger
) {
    /** @return the movement-refresh [Job] this crossing started, so an OS execution window can wait for it. */
    suspend fun handle(crossing: GeofenceCrossing): Job? {
        // The receiver's stamp, not a fresh read; see [GeofenceCrossing.receivedAtSeconds].
        val timestamp = crossing.receivedAtSeconds
        // The only provenance a GMS callback carries: its fix cannot postdate the transition it
        // triggered, so dwell attribution compares it with the registration's stamp.
        val triggeringFixElapsedMs = crossing.triggeringLocation?.elapsedRealtimeNanos
            ?.takeIf { it > 0L }
            ?.let { it / NANOS_PER_MILLI }

        // Cold-start callbacks can beat the posted launch initialization, so establish the persisted
        // user's session synchronously first.
        polygonController.beginUserSessionForCurrentUser()
        // A previous callback can have staged a transition before the file outbox was writable.
        // Recover it before interpreting this edge so an ENTER followed by EXIT stays ordered.
        transitionProcessor.recoverPendingTransitions()

        val userStateGeneration = regionStore.userStateGeneration()
        val routableIds = regionStore.getRoutableRegisteredIds()
        val (routableTriggeringIds, unroutableIds) = crossing.geofenceIds.partition { it in routableIds }
        // Routing is unarmed after an identify or a fresh add; such a registration is not an orphan.
        // Drop its callback, and evict only ids the bookkeeping no longer claims.
        val unarmedTriggerIds = if (unroutableIds.isEmpty()) {
            emptyList()
        } else {
            val registeredIds = regionStore.getRegisteredIds()
            val (unarmedIds, orphanIds) = unroutableIds.partition { it in registeredIds }
            orphanIds.forEach { logger.logTransitionDroppedUnknownId(it) }
            if (orphanIds.isNotEmpty()) {
                // Result ignored: a failed removal self-heals on the next orphan event.
                registrar.removeGeofencesByIds(orphanIds)
            }
            // The movement trigger has no business meaning and is the only refresh path that runs
            // without the app opening, so its EXIT is routed anyway to re-arm an unarmed session.
            val (triggerIds, businessIds) = unarmedIds.partition { it == GeofenceConstants.MOVEMENT_TRIGGER_ID }
            businessIds.forEach { logger.logTransitionDroppedUnarmedId(it) }
            triggerIds
        }

        // Circles first: the refresh this batch starts can evict one, and `requireRegistered` would
        // drop its EXIT. Trigger before polygons: their GMS awaits would spend the budget before
        // the refresh job exists.
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
                    GeofenceCrossingTransition.DWELL ->
                        logger.logUnknownTransition(geofenceId, crossing.rawTransitionCode)
                    GeofenceCrossingTransition.UNSUPPORTED ->
                        logger.logUnknownTransition(geofenceId, crossing.rawTransitionCode)
                }
                return@forEach
            }

            val transition = when (crossing.transition) {
                GeofenceCrossingTransition.ENTER -> Event.GeofenceTransition.ENTER
                GeofenceCrossingTransition.DWELL -> Event.GeofenceTransition.DWELL
                GeofenceCrossingTransition.EXIT -> Event.GeofenceTransition.EXIT
                GeofenceCrossingTransition.UNSUPPORTED -> {
                    logger.logUnknownTransition(geofenceId, crossing.rawTransitionCode)
                    return@forEach
                }
            }

            if (transition == Event.GeofenceTransition.DWELL) {
                dwellCoordinator.onNativeDwell(
                    geofenceId = geofenceId,
                    observedAtSeconds = timestamp,
                    triggeringFixElapsedMs = triggeringFixElapsedMs,
                    expectedUserStateGeneration = userStateGeneration,
                    observedAtElapsedMs = crossing.receivedAtElapsedMs
                )
            } else {
                // Re-read under the transition lock just before this ENTER commits (see `admits`): a
                // read here can predate a concurrent ENTER's commit and start a second visit.
                var wasInside = transition == Event.GeofenceTransition.ENTER &&
                    geofenceId in regionStore.getEnteredIds()
                if (transition == Event.GeofenceTransition.EXIT) {
                    val current = dwellCoordinator.onNativeExit(
                        geofenceId = geofenceId,
                        exitedAtSeconds = timestamp,
                        triggeringFixElapsedMs = triggeringFixElapsedMs,
                        expectedUserStateGeneration = userStateGeneration,
                        exitedAtElapsedMs = crossing.receivedAtElapsedMs
                    )
                    // GMS reordered it behind a later inside fix, so the device is still here.
                    if (!current) {
                        logger.logTransitionDroppedSuperseded(geofenceId, transition.name)
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
                    requireRegistered = true,
                    endsVisitByTimestamp = false,
                    admits = {
                        if (transition == Event.GeofenceTransition.ENTER) {
                            wasInside = geofenceId in regionStore.getEnteredIds()
                        }
                        dwellCoordinator.admitsNativeEdge(geofenceId, transition, triggeringFixElapsedMs)
                    }
                )
                if (
                    transition == Event.GeofenceTransition.ENTER &&
                    geofenceId in regionStore.getEnteredIds()
                ) {
                    dwellCoordinator.onEnter(
                        geofenceId,
                        timestamp,
                        userStateGeneration,
                        beginsNewVisit = !wasInside,
                        entryFixElapsedMs = triggeringFixElapsedMs,
                        enteredAtElapsedMs = crossing.receivedAtElapsedMs
                    )
                }
            }
        }
        return movementRefreshJob
    }

    private fun handleMovementTrigger(crossing: GeofenceCrossing, userStateGeneration: Long): Job? {
        if (crossing.transition != GeofenceCrossingTransition.EXIT) {
            logger.logMovementTriggerIgnoredNonExit(crossing.transitionName)
            return null
        }
        val triggeringLocation = crossing.triggeringLocation
        // A lambda: the polygon callback awaits GMS and must not run before the refresh job exists.
        return services.onMovementTriggerExit(
            latitude = crossing.latitude,
            longitude = crossing.longitude,
            movementTriggerRadius = {
                polygonController.onMovementTriggerExit(
                    triggeringLocation = triggeringLocation,
                    expectedUserStateGeneration = userStateGeneration
                )
            },
            fixQuality = GeofenceFixQuality(
                fixElapsedRealtimeMillis = triggeringLocation?.elapsedRealtimeNanos
                    ?.takeIf { it > 0L }
                    ?.let { it / NANOS_PER_MILLI },
                horizontalAccuracyMeters = triggeringLocation?.takeIf { it.hasAccuracy() }?.accuracy
            )
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
