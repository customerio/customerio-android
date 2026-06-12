package io.customer.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresPermission
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
    private val permissionChecker: GeofencePermissionChecker,
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

    /**
     * Replaces currently-registered geofences with [regions]; empty list
     * disables the broadcast receiver. Business IDs in [existingBusinessIds]
     * are left alone — only additions (regions − existing) are sent to GMS.
     *
     * Re-upserting a same-ID geofence triggers GMS state reconciliation that
     * can fire spurious EXIT events; skipping the overlap avoids that.
     * Default `emptySet()` means "OS state unknown, register everything".
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    suspend fun replaceGeofences(
        regions: List<GeofenceRegion>,
        existingBusinessIds: Set<String> = emptySet()
    ): Result<Unit> = replaceGeofencesInternal(
        regions = regions,
        movementInitialTrigger = GeofencingRequest.INITIAL_TRIGGER_ENTER,
        existingBusinessIds = existingBusinessIds
    )

    /**
     * Boot-restore variant of [replaceGeofences]: registers the movement
     * trigger with `INITIAL_TRIGGER_EXIT` so the OS evaluates current
     * location at register time. If the user moved beyond the cached circle
     * while the device was off, EXIT fires immediately and the next
     * handleMovement self-heals with real coordinates.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    suspend fun replaceGeofencesForBootRestore(regions: List<GeofenceRegion>): Result<Unit> =
        replaceGeofencesInternal(regions, movementInitialTrigger = GeofencingRequest.INITIAL_TRIGGER_EXIT)

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    private suspend fun replaceGeofencesInternal(
        regions: List<GeofenceRegion>,
        movementInitialTrigger: Int,
        existingBusinessIds: Set<String> = emptySet()
    ): Result<Unit> {
        if (regions.isEmpty()) {
            // No geofences to register => disable the receiver so we don't burn
            // resources listening for events that can't fire. Covers both the
            // fresh-account-with-no-geofences case and the account-transitioned-to-0
            // case (where stale cleanup just removed the previous registrations).
            receiverToggle.setEnabled(false)
            logger.logGeofencesRegistered(0)
            return Result.success(Unit)
        }
        if (!permissionChecker.hasRequiredLocationPermissions()) {
            logger.logMissingPermission("ACCESS_FINE_LOCATION")
            return Result.failure(SecurityException("Required location permissions not granted"))
        }

        // Split because GMS allows one initial-trigger per batch — business
        // uses INITIAL_TRIGGER_ENTER, movement is caller-controlled.
        val movementTrigger = regions.filter { it.id == GeofenceConstants.MOVEMENT_TRIGGER_ID }
        val (businessToAdd, businessKept) = regions
            .filter { it.id != GeofenceConstants.MOVEMENT_TRIGGER_ID }
            .partition { it.id !in existingBusinessIds }

        if (movementTrigger.isNotEmpty()) {
            // GMS retains per-ID transition state across same-ID re-registers, so
            // a just-fired EXIT keeps the ID stuck OUTSIDE and blocks future EXITs
            // at the new center. Removing first forces a fresh state machine.
            removeGeofencesByIds(listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID))
            val result = registerBatch(movementTrigger, initialTrigger = movementInitialTrigger)
            if (result.isFailure) return result
        }

        if (businessToAdd.isNotEmpty()) {
            val result = registerBatch(businessToAdd, initialTrigger = GeofencingRequest.INITIAL_TRIGGER_ENTER)
            if (result.isFailure) {
                // Roll back the movement trigger so we don't leave a safety-net
                // geofence alone in the OS with no business regions to act on.
                if (movementTrigger.isNotEmpty()) {
                    removeGeofencesByIds(movementTrigger.map { it.id })
                }
                return result
            }
        }

        receiverToggle.setEnabled(true)
        val registeredCount = movementTrigger.size + businessToAdd.size
        logger.logGeofencesRegistered(registeredCount)
        if (businessKept.isNotEmpty()) {
            logger.logBusinessGeofencesKept(businessKept.size)
        }
        return Result.success(Unit)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
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
            try {
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
            } catch (e: SecurityException) {
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

    private fun GeofenceRegion.toGmsGeofence(): Geofence {
        return Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(latitude, longitude, radius)
            .setTransitionTypes(toGmsTransitionTypes())
            .setExpirationDuration(GeofenceConstants.GEOFENCE_EXPIRATION_NEVER)
            .build()
    }
}
