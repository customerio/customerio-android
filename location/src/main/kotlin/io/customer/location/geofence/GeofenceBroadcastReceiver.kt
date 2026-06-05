package io.customer.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.customer.location.geofence.di.geofenceCooldownFilter
import io.customer.location.geofence.di.geofenceEventScheduler
import io.customer.location.geofence.di.geofenceLogger
import io.customer.location.geofence.di.geofenceManager
import io.customer.location.geofence.di.geofenceRegionStore
import io.customer.location.geofence.di.geofenceServices
import io.customer.location.geofence.di.pendingGeofenceDeliveryStore
import io.customer.location.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
            longitude = location?.longitude
        )
    }

    @VisibleForTesting
    internal suspend fun dispatchTransition(
        gmsTransitionType: Int,
        triggeringGeofenceIds: List<String>,
        latitude: Double?,
        longitude: Double?
    ) {
        val logger = SDKComponent.geofenceLogger
        val timestamp = SDKComponent.clock.currentTimeSeconds()
        val androidComponent = SDKComponent.android()
        val scheduler = androidComponent.geofenceEventScheduler
        val cooldownFilter = androidComponent.geofenceCooldownFilter
        // Defense-in-depth against orphans (failed clearAll, app-data wipe, SDK
        // ID-format changes): events for unregistered IDs are dropped and the OS-side
        // registration is removed so it stops firing.
        val registeredIds = androidComponent.geofenceRegionStore.getRegisteredIds()
        val (knownIds, unknownIds) = triggeringGeofenceIds.partition { it in registeredIds }
        if (unknownIds.isNotEmpty()) {
            unknownIds.forEach { logger.logTransitionDroppedUnknownId(it) }
            // Result ignored — a failed removal self-heals on the next orphan event.
            androidComponent.geofenceManager.removeGeofencesByIds(unknownIds)
        }

        knownIds.forEach { geofenceId ->
            if (geofenceId == GeofenceConstants.MOVEMENT_TRIGGER_ID) {
                // ENTER fires on every re-registration and boot-restore can fire
                // EXIT. Only EXIT drives a refresh.
                if (gmsTransitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    androidComponent.geofenceServices.onMovementTriggerExit(latitude, longitude)
                } else {
                    logger.logMovementTriggerIgnoredNonExit(transitionName(gmsTransitionType))
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

            if (!cooldownFilter.tryAcquire(geofenceId, transition)) {
                logger.logTransitionSuppressed(geofenceId, transition.name)
                return@forEach
            }
            logger.logTransitionEmitting(geofenceId, transition.name)

            // Record the transition durably before scheduling. Two channels then
            // race to deliver it exactly once, arbitrated by the shared store's
            // atomic claim: the WorkManager worker (direct HTTP, survives process
            // death) and the foreground flush (analytics pipeline). Append first
            // so a WorkManager scheduling failure still leaves the entry for the
            // flush; isolate the scheduler so it can't abandon the rest of the batch.
            val entry = PendingGeofenceDelivery(
                geofenceId = geofenceId,
                transition = transition,
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp
            )
            androidComponent.pendingGeofenceDeliveryStore.append(entry)
            try {
                scheduler.schedule(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.logSchedulerFailed(geofenceId, transition.name, e.message)
            }
        }
    }

    private fun transitionName(gmsTransitionType: Int): String = when (gmsTransitionType) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
        Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
        Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
        else -> "UNKNOWN($gmsTransitionType)"
    }
}
