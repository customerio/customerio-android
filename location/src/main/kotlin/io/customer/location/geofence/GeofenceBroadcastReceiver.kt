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
import io.customer.location.geofence.di.geofenceServices
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
        val eventBus = SDKComponent.eventBus

        triggeringGeofenceIds.forEach { geofenceId ->
            if (geofenceId == GeofenceConstants.MOVEMENT_TRIGGER_ID) {
                // Movement-trigger geofence is registered with NO_INITIAL_TRIGGER so it
                // should only ever fire EXIT; defensive guard in case the OS surprises us.
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

            // Parallel delivery: WorkManager (survives process death) + EventBus (analytics pipeline).
            // Isolate the scheduler so a WorkManager failure doesn't skip EventBus or abandon the batch.
            try {
                scheduler.schedule(
                    geofenceId = geofenceId,
                    transition = transition,
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = timestamp
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.logSchedulerFailed(geofenceId, transition.name, e.message)
            }

            val properties = buildMap<String, Any> {
                put("geofence_id", geofenceId)
                put("transition_type", transition.name.lowercase())
                latitude?.let { put("latitude", it) }
                longitude?.let { put("longitude", it) }
                put("timestamp", timestamp)
            }
            eventBus.publish(
                Event.GeofenceTransitionEvent(
                    geofenceId = geofenceId,
                    transition = transition,
                    properties = properties
                )
            )
        }
    }

    private fun transitionName(gmsTransitionType: Int): String = when (gmsTransitionType) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
        Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
        Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
        else -> "UNKNOWN($gmsTransitionType)"
    }
}
