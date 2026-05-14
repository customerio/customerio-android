package io.customer.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.customer.location.geofence.di.geofenceEventScheduler
import io.customer.location.geofence.di.geofenceLogger
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives OS geofence transition callbacks and dispatches them to the SDK. */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync keeps the process alive until WorkManager has committed the work spec;
        // without it the OS may kill us between enqueue and persist.
        val pendingResult = goAsync()
        SDKComponent.setupAndroidComponent(context = context)
        val scope = SDKComponent.scopeProvider.geofenceScope

        scope.launch {
            try {
                handleGeofencingEvent(GeofencingEvent.fromIntent(intent))
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
        val eventBus = SDKComponent.eventBus

        triggeringGeofenceIds.forEach { geofenceId ->
            if (geofenceId == GeofenceConstants.MOVEMENT_TRIGGER_ID) {
                // TODO(MBL-1623): dispatch movement-trigger EXIT to GeofenceServices.onMovementTriggerExit
                logger.logMovementTriggerSkipped()
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

            logger.logTransitionEmitting(geofenceId, transition.name)

            // Parallel delivery: WorkManager (survives process death) + EventBus (analytics pipeline).
            scheduler.schedule(
                geofenceId = geofenceId,
                transition = transition,
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp
            )

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
}
