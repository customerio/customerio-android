package io.customer.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import io.customer.location.geofence.di.geofenceLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent

/** Receives OS geofence transition callbacks and dispatches them to the SDK. */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        SDKComponent.setupAndroidComponent(context = context)

        try {
            handleGeofenceEvent(intent)
        } catch (e: Exception) {
            SDKComponent.geofenceLogger.logSyncFailed("BroadcastReceiver error: ${e.message}")
        }
    }

    private fun handleGeofenceEvent(intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        val logger = SDKComponent.geofenceLogger

        if (geofencingEvent.hasError()) {
            logger.logGeofencingError(geofencingEvent.errorCode)
            return
        }

        val transitionType = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val location = geofencingEvent.triggeringLocation

        triggeringGeofences.forEach { geofence ->
            logger.logTransitionReceived(
                geofence.requestId,
                "type=$transitionType at (${location?.latitude}, ${location?.longitude})"
            )
        }
    }
}
