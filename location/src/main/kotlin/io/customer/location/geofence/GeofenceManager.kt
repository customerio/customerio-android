package io.customer.location.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.customer.location.geofence.GeofenceConstants.GEOFENCE_EXPIRATION_NEVER
import io.customer.location.geofence.GeofenceConstants.GEOFENCE_TRANSITION_ACTION
import io.customer.sdk.core.util.Logger

/**
 * Manages Android geofencing through Google Play Services GeofencingClient.
 * Handles registration, removal, and permission checks.
 */
internal class GeofenceManager(
    private val context: Context,
    private val logger: Logger
) {
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = GEOFENCE_TRANSITION_ACTION
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * Checks if the app has the necessary location permissions for geofencing.
     */
    fun hasLocationPermission(): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return hasFineLocation || hasCoarseLocation
    }

    /**
     * Adds geofences to the Android geofencing system.
     */
    fun addGeofences(regions: List<GeofenceRegion>, onComplete: (Boolean) -> Unit) {
        if (regions.isEmpty()) {
            logger.debug("No geofences to add")
            onComplete(true)
            return
        }

        if (!hasLocationPermission()) {
            logger.error("Cannot add geofences: location permission not granted")
            onComplete(false)
            return
        }

        val geofences = regions.map { region ->
            Geofence.Builder()
                .setRequestId(region.id)
                .setCircularRegion(region.latitude, region.longitude, region.radius.toFloat())
                .setExpirationDuration(GEOFENCE_EXPIRATION_NEVER)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT or
                        Geofence.GEOFENCE_TRANSITION_DWELL
                )
                .setLoiteringDelay(region.dwellTimeMs.toInt())
                .build()
        }

        val request = GeofencingRequest.Builder().apply {
            setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            addGeofences(geofences)
        }.build()

        try {
            geofencingClient.addGeofences(request, geofencePendingIntent).apply {
                addOnSuccessListener {
                    logger.debug("Successfully added ${regions.size} geofences")
                    onComplete(true)
                }
                addOnFailureListener { exception ->
                    logger.error("Failed to add geofences: ${exception.message}")
                    onComplete(false)
                }
            }
        } catch (e: SecurityException) {
            logger.error("Security exception adding geofences: ${e.message}")
            onComplete(false)
        }
    }

    /**
     * Removes geofences by their IDs.
     */
    fun removeGeofences(ids: List<String>, onComplete: (Boolean) -> Unit) {
        if (ids.isEmpty()) {
            logger.debug("No geofences to remove")
            onComplete(true)
            return
        }

        geofencingClient.removeGeofences(ids).apply {
            addOnSuccessListener {
                logger.debug("Successfully removed ${ids.size} geofences")
                onComplete(true)
            }
            addOnFailureListener { exception ->
                logger.error("Failed to remove geofences: ${exception.message}")
                onComplete(false)
            }
        }
    }

    /**
     * Removes all geofences.
     */
    fun removeAllGeofences(onComplete: (Boolean) -> Unit) {
        geofencingClient.removeGeofences(geofencePendingIntent).apply {
            addOnSuccessListener {
                logger.debug("Successfully removed all geofences")
                onComplete(true)
            }
            addOnFailureListener { exception ->
                logger.error("Failed to remove all geofences: ${exception.message}")
                onComplete(false)
            }
        }
    }
}
