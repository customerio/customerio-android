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
        if (geofencingEvent == null) return
        val logger = SDKComponent.geofenceLogger

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

        val triggeringGeofenceIds = geofencingEvent.triggeringGeofences?.map { it.requestId } ?: return
        val location = geofencingEvent.triggeringLocation
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
        androidComponent.secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }?.let {
            androidComponent.polygonGeofenceServiceController.beginUserSession(it)
        }
        // A previous callback can have staged a transition before the file outbox was writable.
        // Recover it before interpreting this edge so an ENTER followed by EXIT stays ordered.
        androidComponent.geofenceBusinessTransitionProcessor.recoverPendingTransitions()
        // Defense-in-depth against orphans (failed clearAll, app-data wipe, SDK
        // ID-format changes): events for unregistered IDs are dropped and the OS-side
        // registration is removed so it stops firing.
        val userStateGeneration = androidComponent.geofenceRegionStore.userStateGeneration()
        val registeredIds = androidComponent.geofenceRegionStore.getRoutableRegisteredIds()
        val (knownIds, unknownIds) = triggeringGeofenceIds.partition { it in registeredIds }
        if (unknownIds.isNotEmpty()) {
            unknownIds.forEach { logger.logTransitionDroppedUnknownId(it) }
            // Result ignored — a failed removal self-heals on the next orphan event.
            androidComponent.geofenceManager.removeGeofencesByIds(unknownIds)
        }

        var movementRefreshJob: Job? = null
        knownIds.forEach { geofenceId ->
            if (geofenceId == GeofenceConstants.MOVEMENT_TRIGGER_ID) {
                // ENTER fires on every re-registration and boot-restore can fire
                // EXIT. Only EXIT drives a refresh.
                if (gmsTransitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    movementRefreshJob = androidComponent.geofenceServices.onMovementTriggerExit(latitude, longitude)
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
                    else -> logger.logUnknownTransition(gmsTransitionType)
                }
                return@forEach
            }

            val transition = when (gmsTransitionType) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> Event.GeofenceTransition.ENTER
                Geofence.GEOFENCE_TRANSITION_EXIT -> Event.GeofenceTransition.EXIT
                else -> {
                    logger.logUnknownTransition(gmsTransitionType)
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
