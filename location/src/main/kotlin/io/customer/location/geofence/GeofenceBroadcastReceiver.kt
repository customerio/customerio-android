package io.customer.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.customer.location.ModuleLocation
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.pipeline.DataPipeline
import io.customer.sdk.core.util.Logger

/**
 * BroadcastReceiver that handles geofence transition events from Android.
 * Sends corresponding events to Customer.io when geofences are entered, exited, or dwelled.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        val logger = SDKComponent.logger

        if (geofencingEvent.hasError()) {
            logger.error("Geofence error: ${geofencingEvent.errorCode}")
            return
        }

        val transitionType = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val triggeringLocation = geofencingEvent.triggeringLocation

        // Get the module instance to access geofence metadata
        val geofenceServices = try {
            ModuleLocation.instance().locationServices.geofenceServices as? GeofenceServicesImpl
        } catch (e: Exception) {
            logger.error("Failed to get geofence services: ${e.message}")
            null
        }

        val dataPipeline = SDKComponent.getOrNull<DataPipeline>()
        if (dataPipeline == null) {
            logger.debug("DataPipeline not available, skipping geofence event")
            return
        }

        if (!dataPipeline.isUserIdentified) {
            logger.debug("User not identified, skipping geofence event")
            return
        }

        triggeringGeofences.forEach { geofence ->
            val region = geofenceServices?.getGeofenceById(geofence.requestId)
            sendGeofenceEvent(dataPipeline, logger, geofence, transitionType, region, triggeringLocation)
        }
    }

    private fun sendGeofenceEvent(
        dataPipeline: DataPipeline,
        logger: Logger,
        geofence: Geofence,
        transitionType: Int,
        region: GeofenceRegion?,
        triggeringLocation: Location?
    ) {
        val eventName = when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceConstants.EVENT_GEOFENCE_ENTERED
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceConstants.EVENT_GEOFENCE_EXITED
            Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceConstants.EVENT_GEOFENCE_DWELLED
            else -> {
                logger.debug("Unknown geofence transition type: $transitionType")
                return
            }
        }

        val transitionName = when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "dwell"
            else -> "unknown"
        }

        val properties = mutableMapOf<String, Any>(
            "geofence_id" to geofence.requestId,
            "transition_type" to transitionName
        )

        // Add region metadata if available
        region?.let {
            properties["latitude"] = it.latitude
            properties["longitude"] = it.longitude
            properties["radius"] = it.radius
            it.name?.let { name -> properties["geofence_name"] = name }
            it.customData?.let { customData -> properties.putAll(customData) }

            // Calculate distance from triggering location to geofence center
            if (triggeringLocation != null) {
                val geofenceCenter = Location("").apply {
                    latitude = it.latitude
                    longitude = it.longitude
                }
                val distance = triggeringLocation.distanceTo(geofenceCenter)
                properties["distance"] = distance.toDouble() // in meters
            }
        }

        logger.debug("Sending geofence event: $eventName for ${geofence.requestId}")
        dataPipeline.track(
            name = eventName,
            properties = properties
        )
    }
}
