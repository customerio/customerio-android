package io.customer.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import io.customer.geofence.di.geofenceBusinessTransitionProcessor
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceManager
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.geofenceServices
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Receives OS geofence transition callbacks and dispatches them to the SDK. */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync keeps the process alive until WorkManager has committed the work spec;
        // without it the OS may kill us between enqueue and persist.
        val pendingResult = goAsync()
        try {
            SDKComponent.setupAndroidComponent(context = context)
            val scope = SDKComponent.scopeProvider.geofenceScope
            launchTransitionHandler(scope, intent, pendingResult)
        } catch (e: Throwable) {
            // Setup threw before the coroutine could register its finally — release the PendingResult here.
            SDKComponent.geofenceLogger.logSyncFailed("BroadcastReceiver setup failed: ${e.message}")
            pendingResult.finish()
        }
    }

    private fun launchTransitionHandler(
        scope: CoroutineScope,
        intent: Intent,
        pendingResult: PendingResult
    ) {
        scope.launch {
            try {
                handleGeofencingEvent(GeofencingEvent.fromIntent(intent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SDKComponent.geofenceLogger.logSyncFailed("BroadcastReceiver error: ${e.message}")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    @VisibleForTesting
    internal suspend fun handleGeofencingEvent(geofencingEvent: GeofencingEvent?) {
        val logger = SDKComponent.geofenceLogger
        if (geofencingEvent == null) {
            logger.logInfo("broadcast_unparseable_intent")
            return
        }

        if (geofencingEvent.hasError()) {
            logger.logGeofencingError(geofencingEvent.errorCode)
            if (geofencingEvent.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                // GMS requires callers to re-register after this error. Invalidate only our claim
                // that OS registrations are live; cached definitions and containment survive so
                // the next foreground/location refresh can restore them without duplicate ENTERs.
                SDKComponent.android().polygonGeofenceServiceController.invalidateOsRegistrationState()
            }
            return
        }

        val triggeringGeofenceIds = geofencingEvent.triggeringGeofences?.map { it.requestId }
            ?: run {
                logger.logInfo("broadcast_no_triggering_geofences")
                return
            }
        val location = geofencingEvent.triggeringLocation
        // Logged here, before any routing decision and before the Location is narrowed to a pair
        // of doubles. This is the only place the OS's own triggering fix — accuracy, age, mock
        // flag and all — still exists.
        logger.logCallbackReceived(
            geofenceIds = triggeringGeofenceIds,
            transitionName = transitionName(geofencingEvent.geofenceTransition),
            location = location,
            source = if (location != null) GeofenceLogTail.FixSource.OS_TRIGGER else GeofenceLogTail.FixSource.NONE
        )
        if (location == null) {
            logger.logTransitionWithoutLocation()
        }

        dispatchTransition(
            gmsTransitionType = geofencingEvent.geofenceTransition,
            triggeringGeofenceIds = triggeringGeofenceIds,
            latitude = location?.latitude,
            longitude = location?.longitude,
            triggeringLocation = location
        )
    }

    @VisibleForTesting
    internal suspend fun dispatchTransition(
        gmsTransitionType: Int,
        triggeringGeofenceIds: List<String>,
        latitude: Double?,
        longitude: Double?,
        triggeringLocation: Location? = null
    ) {
        val logger = SDKComponent.geofenceLogger
        val timestamp = SDKComponent.clock.currentTimeSeconds()
        val dispatchStartUptimeMs = SDKComponent.clock.elapsedRealtime()
        val androidComponent = SDKComponent.android()
        // Cold-start callbacks can beat the posted launch initialization. Establish the persisted
        // secure user's session synchronously; legacy installs without an owner are migrated by the
        // store without discarding their already-live OS registrations.
        androidComponent.polygonGeofenceServiceController.beginUserSessionForCurrentUser()
        // A previous callback can have staged a transition before the file outbox was writable.
        // Recover it before interpreting this edge so an ENTER followed by EXIT stays ordered.
        androidComponent.geofenceBusinessTransitionProcessor.recoverPendingTransitions()
        // Defense-in-depth against orphans (failed clearAll, app-data wipe, SDK
        // ID-format changes): events for unregistered IDs are dropped and the OS-side
        // registration is removed so it stops firing.
        val userStateGeneration = androidComponent.geofenceRegionStore.userStateGeneration()
        val routableIds = androidComponent.geofenceRegionStore.getRoutableRegisteredIds()
        val (routableTriggeringIds, unroutableIds) = triggeringGeofenceIds.partition { it in routableIds }
        // An identify clears routing and only the completing refresh re-arms it, so between the two
        // a live registration is unroutable without being an orphan. Removing it there strands it:
        // business fences register with INITIAL_TRIGGER_ENTER and routing arms only after the add,
        // so a fence the refresh just added can report ENTER while routing still reads empty.
        // Evicting on that would remove a fence the same pass then publishes registered AND
        // routable, and every later refresh reads that as unchanged and never re-adds it. Only
        // evict what the bookkeeping no longer claims, and drop the rest — routing is what
        // authorizes a business event.
        val unarmedTriggerIds = if (unroutableIds.isEmpty()) {
            emptyList()
        } else {
            val registeredIds = androidComponent.geofenceRegionStore.getRegisteredIds()
            val (unarmedIds, orphanIds) = unroutableIds.partition { it in registeredIds }
            orphanIds.forEach { logger.logTransitionDroppedUnknownId(it) }
            if (orphanIds.isNotEmpty()) {
                // Result ignored — a failed removal self-heals on the next orphan event.
                androidComponent.geofenceManager.removeGeofencesByIds(orphanIds)
            }
            // The movement trigger is SDK-owned and carries no business meaning — routing it only
            // re-fetches. It is also the only refresh path that runs without the app being opened,
            // so dropping its EXIT here would leave an unarmed session with nothing to re-arm it
            // until the next launch. handleMovement waits for the slot and runs as the current user.
            val (triggerIds, businessIds) = unarmedIds.partition { it == GeofenceConstants.MOVEMENT_TRIGGER_ID }
            businessIds.forEach { logger.logTransitionDroppedUnarmedId(it) }
            triggerIds
        }
        // Circles, then the movement trigger, then polygons, whatever order GMS delivered.
        //
        // The polygon group also SPENDS this budget: an undecided verdict awaits a precise fix for
        // up to PolygonGeofenceServiceController.FRESH_FIX_TIMEOUT_MS, and that wait lands before
        // the join below. Sizing lives on that constant; the arithmetic is against this one.
        //
        // Circles first because the refresh this batch starts can evict one under the monitoring
        // cap, and its `requireRegistered` check would then drop an EXIT the OS had already
        // delivered. The trigger next because the polygon handlers await GMS, so leaving it behind
        // them spends the dispatch budget before the refresh job exists and the receiver finishes
        // without holding the window open for it. Sorting is stable, so GMS order survives within
        // each group.
        val polygonIds = androidComponent.geofenceRegionStore.getCachedRegions()
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
                // ENTER fires on every re-registration and boot-restore can fire
                // EXIT. Only EXIT drives a refresh.
                if (gmsTransitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    // Handed over unevaluated. It awaits GMS, and doing that here would spend the
                    // broadcast budget before the refresh job exists.
                    movementRefreshJob = androidComponent.geofenceServices.onMovementTriggerExit(
                        latitude = latitude,
                        longitude = longitude,
                        movementTriggerRadius = {
                            androidComponent.polygonGeofenceServiceController.onMovementTriggerExit(
                                triggeringLocation = triggeringLocation,
                                expectedUserStateGeneration = userStateGeneration
                            )
                        }
                    )
                } else {
                    logger.logMovementTriggerIgnoredNonExit(transitionName(gmsTransitionType))
                }
                return@forEach
            }

            val region = androidComponent.geofenceRegionStore.getCachedRegion(geofenceId)
            if (region == null && androidComponent.geofenceRegionStore.getRegisteredRegion(geofenceId) != null) {
                // The server removed this region, but an earlier GMS removal failed. Its retained
                // definition is a routing tombstone only; never manufacture business activity for
                // a fence that no longer exists. Every orphan callback also retries OS cleanup.
                logger.logTransitionDroppedRetiredId(geofenceId)
                androidComponent.geofenceManager.removeGeofencesByIds(listOf(geofenceId))
                return@forEach
            }
            if (region?.isPolygon == true) {
                when (gmsTransitionType) {
                    Geofence.GEOFENCE_TRANSITION_ENTER ->
                        androidComponent.polygonGeofenceServiceController.activate(
                            polygonId = geofenceId,
                            triggeringLocation = triggeringLocation,
                            expectedUserStateGeneration = userStateGeneration,
                            expectedRegionRevision = region.transitionRevision()
                        )
                    Geofence.GEOFENCE_TRANSITION_EXIT ->
                        androidComponent.polygonGeofenceServiceController.onCoarseExit(
                            polygonId = geofenceId,
                            triggeringLocation = triggeringLocation,
                            expectedUserStateGeneration = userStateGeneration,
                            expectedRegionRevision = region.transitionRevision()
                        )
                    else -> logger.logUnknownTransition(geofenceId, gmsTransitionType)
                }
                return@forEach
            }

            val transition = when (gmsTransitionType) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> Event.GeofenceTransition.ENTER
                Geofence.GEOFENCE_TRANSITION_EXIT -> Event.GeofenceTransition.EXIT
                else -> {
                    logger.logUnknownTransition(geofenceId, gmsTransitionType)
                    return@forEach
                }
            }

            androidComponent.geofenceBusinessTransitionProcessor.process(
                geofenceId = geofenceId,
                transition = transition,
                timestampSeconds = timestamp,
                enforceConfiguredTransition = region != null,
                expectedRegionRevision = region?.transitionRevision(),
                expectedUserStateGeneration = userStateGeneration,
                requireRegistered = true
            )
        }

        // Hold the goAsync window open until the refresh lands so the OS doesn't kill a
        // backgrounded process mid-re-registration. Waits only for what's left of the
        // dispatch budget — the persistence/GMS awaits above count against it. A timeout
        // ends the wait only, not the refresh (it runs on the longer-lived services scope).
        movementRefreshJob?.let { job ->
            val remainingBudgetMs = DISPATCH_WAIT_BUDGET_MS - (SDKComponent.clock.elapsedRealtime() - dispatchStartUptimeMs)
            if (remainingBudgetMs > 0) {
                withTimeoutOrNull(remainingBudgetMs) { job.join() }
            }
        }
    }

    private fun transitionName(gmsTransitionType: Int): String = when (gmsTransitionType) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
        Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
        Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
        else -> "UNKNOWN($gmsTransitionType)"
    }

    internal companion object {
        // goAsync grants ~10s before the OS considers the receiver blocked; total budget for
        // one dispatch (persistence + GMS awaits + movement-refresh wait), with headroom.
        private const val DISPATCH_WAIT_BUDGET_MS = 8_000L
    }
}
