package io.customer.location.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Wraps GeofencingClient to register/remove geofences with the OS. */
internal class GeofenceManager(
    private val context: Context,
    private val client: GeofencingClient,
    private val receiverToggle: GeofenceReceiverToggle,
    private val logger: GeofenceLogger
) {

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(
            context,
            GeofenceConstants.PENDING_INTENT_REQUEST_CODE,
            intent,
            flags
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    suspend fun addGeofences(regions: List<GeofenceRegion>): Result<Unit> {
        if (regions.isEmpty()) {
            logger.logGeofencesRegistered(0)
            return Result.success(Unit)
        }
        if (!hasRequiredPermissions()) {
            return Result.failure(SecurityException("Required location permissions not granted"))
        }

        // Split into separate requests: GMS only supports one initial-trigger per request.
        // Movement trigger uses no initial trigger to avoid spurious EXIT on re-registration.
        // Business geofences use INITIAL_TRIGGER_ENTER for immediate detection if already inside.
        val movementTrigger = regions.filter { it.id == GeofenceConstants.MOVEMENT_TRIGGER_ID }
        val businessGeofences = regions.filter { it.id != GeofenceConstants.MOVEMENT_TRIGGER_ID }

        if (movementTrigger.isNotEmpty()) {
            val result = registerBatch(movementTrigger, initialTrigger = GeofenceConstants.NO_INITIAL_TRIGGER)
            if (result.isFailure) return result
        }

        if (businessGeofences.isNotEmpty()) {
            val result = registerBatch(businessGeofences, initialTrigger = GeofencingRequest.INITIAL_TRIGGER_ENTER)
            if (result.isFailure) {
                // Clean up movement trigger if business batch fails to avoid orphaned geofences
                if (movementTrigger.isNotEmpty()) {
                    removeGeofencesByIds(movementTrigger.map { it.id })
                }
                return result
            }
        }

        receiverToggle.setEnabled(true)
        logger.logGeofencesRegistered(regions.size)
        return Result.success(Unit)
    }

    private suspend fun registerBatch(
        regions: List<GeofenceRegion>,
        initialTrigger: Int
    ): Result<Unit> {
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(initialTrigger)
            .addGeofences(regions.map { it.toGmsGeofence() })
            .build()

        return suspendCancellableCoroutine { cont ->
            try {
                client.addGeofences(request, pendingIntent)
                    .addOnSuccessListener {
                        if (!cont.isActive) return@addOnSuccessListener
                        cont.resume(Result.success(Unit))
                    }
                    .addOnFailureListener { e ->
                        if (!cont.isActive) return@addOnFailureListener
                        logger.logRegistrationFailed(e.message)
                        cont.resume(Result.failure(e))
                    }
            } catch (e: SecurityException) {
                logger.logRegistrationFailed(e.message)
                cont.resume(Result.failure(e))
            }
        }
    }

    suspend fun removeGeofencesByIds(ids: List<String>): Result<Unit> {
        if (ids.isEmpty()) return Result.success(Unit)

        return suspendCancellableCoroutine { cont ->
            client.removeGeofences(ids)
                .addOnSuccessListener {
                    if (!cont.isActive) return@addOnSuccessListener
                    logger.logGeofencesRemoved(ids.size)
                    cont.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    if (!cont.isActive) return@addOnFailureListener
                    logger.logRemovalFailed(e.message)
                    cont.resume(Result.failure(e))
                }
        }
    }

    suspend fun clearAll(): Result<Unit> {
        return suspendCancellableCoroutine { cont ->
            client.removeGeofences(pendingIntent)
                .addOnSuccessListener {
                    if (!cont.isActive) return@addOnSuccessListener
                    receiverToggle.setEnabled(false)
                    logger.logGeofencesCleared()
                    cont.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    if (!cont.isActive) return@addOnFailureListener
                    logger.logRemovalFailed(e.message)
                    cont.resume(Result.failure(e))
                }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation) {
            logger.logMissingPermission("ACCESS_FINE_LOCATION")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBackgroundLocation) {
                logger.logMissingPermission("ACCESS_BACKGROUND_LOCATION (required on Android 10+)")
                return false
            }
        }

        return true
    }

    private fun GeofenceRegion.toGmsGeofence(): Geofence {
        return Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setTransitionTypes(toGmsTransitionTypes())
            .setExpirationDuration(GeofenceConstants.GEOFENCE_EXPIRATION_NEVER)
            .build()
    }
}
