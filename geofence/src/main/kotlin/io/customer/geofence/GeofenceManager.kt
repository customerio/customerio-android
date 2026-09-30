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
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class GeofenceManager(
    private val context: Context,
    private val client: GeofencingClient,
    private val receiverToggle: GeofenceReceiverToggle,
    private val permissionChecker: GeofencePermissionChecker,
    private val logger: GeofenceLogger
) : GeofenceRegistrar {

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

    /** An empty [existingBusinessIds] means OS state is unknown, so everything is registered. */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun replaceGeofences(
        regions: List<GeofenceRegion>,
        existingBusinessIds: Set<String>
    ): Result<Unit> = replaceGeofencesInternal(
        regions = regions,
        // A stale center fires EXIT at register time, so handleMovement re-centers on a real fix. No
        // ENTER: the receiver ignores it, and combining the two stops GMS delivering the later EXIT.
        movementInitialTrigger = GeofencingRequest.INITIAL_TRIGGER_EXIT,
        existingBusinessIds = existingBusinessIds
    )

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun replaceGeofencesForBootRestore(regions: List<GeofenceRegion>): Result<Unit> =
        replaceGeofences(regions)

    /**
     * No prior removal: a same-id add replaces in place, and removing first would leave no trigger
     * and nothing to re-arm it if the add fails or times out.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun replaceMovementTrigger(region: GeofenceRegion): Result<Unit> {
        require(region.id == GeofenceConstants.MOVEMENT_TRIGGER_ID) {
            "movement trigger must use the reserved request id"
        }
        if (!permissionChecker.hasRequiredLocationPermissions()) {
            logger.logMissingPermission("ACCESS_FINE_LOCATION")
            return Result.failure(SecurityException("Required location permissions not granted"))
        }
        return registerBatch(
            regions = listOf(region),
            initialTrigger = GeofencingRequest.INITIAL_TRIGGER_EXIT
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun replaceGeofencesInternal(
        regions: List<GeofenceRegion>,
        movementInitialTrigger: Int,
        existingBusinessIds: Set<String> = emptySet()
    ): Result<Unit> {
        if (regions.isEmpty()) {
            // Only when monitoring is off (the movement trigger is otherwise always included).
            receiverToggle.setEnabled(false)
            logger.logGeofencesRegistered(0)
            return Result.success(Unit)
        }
        if (!permissionChecker.hasRequiredLocationPermissions()) {
            logger.logMissingPermission("ACCESS_FINE_LOCATION")
            return Result.failure(SecurityException("Required location permissions not granted"))
        }

        // GMS allows one initial trigger per batch: business uses ENTER, movement is caller-controlled.
        val movementTrigger = regions.filter { it.id == GeofenceConstants.MOVEMENT_TRIGGER_ID }
        val (businessToAdd, businessKept) = regions
            .filter { it.id != GeofenceConstants.MOVEMENT_TRIGGER_ID }
            .partition { it.id !in existingBusinessIds }

        if (movementTrigger.isNotEmpty()) {
            // GMS keeps per-ID state across same-ID re-registers, so a just-fired EXIT would stay
            // OUTSIDE and block EXITs at the new center. Removing first resets it.
            removeGeofencesByIds(listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID))
            val result = registerBatch(movementTrigger, initialTrigger = movementInitialTrigger)
            if (result.isFailure) return result
        }

        if (businessToAdd.isNotEmpty()) {
            val result = registerBatch(businessToAdd, initialTrigger = GeofencingRequest.INITIAL_TRIGGER_ENTER)
            if (result.isFailure) {
                // Don't leave the movement trigger registered with no business regions.
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

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun registerBatch(
        regions: List<GeofenceRegion>,
        initialTrigger: Int
    ): Result<Unit> {
        // A GMS rejection must fail the sync, never crash the host.
        val request = try {
            GeofencingRequest.Builder()
                .setInitialTrigger(initialTrigger)
                .addGeofences(regions.map { it.toGmsGeofence() })
                .build()
        } catch (e: IllegalArgumentException) {
            logger.logRegistrationFailed(e.message)
            return Result.failure(e)
        }

        return awaitGmsCall("addGeofences") { cont ->
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

    override suspend fun removeGeofencesByIds(ids: List<String>): Result<Unit> {
        if (ids.isEmpty()) return Result.success(Unit)

        return awaitGmsCall("removeGeofences") { cont ->
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

    override suspend fun clearAll(): Result<Unit> {
        return awaitGmsCall("removeGeofences (all)") { cont ->
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

    /**
     * A Task never calls back while Play Services updates, and the caller holds the refresh slot, so
     * an unbounded await wedges later syncs. Nothing holds a resource on the result, so a late
     * success just becomes a spurious failure.
     */
    private suspend fun awaitGmsCall(
        description: String,
        block: (CancellableContinuation<Result<Unit>>) -> Unit
    ): Result<Unit> =
        withTimeoutOrNull(GMS_CALL_TIMEOUT) { suspendCancellableCoroutine(block) }
            ?: run {
                logger.logGmsCallTimedOut(description)
                Result.failure(TimeoutException("GMS $description gave no callback within $GMS_CALL_TIMEOUT"))
            }

    private fun GeofenceRegion.toGmsGeofence(): Geofence {
        return Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(latitude, longitude, radius)
            .setTransitionTypes(toGmsTransitionTypes())
            .setExpirationDuration(GeofenceConstants.GEOFENCE_EXPIRATION_NEVER)
            .build()
    }

    private companion object {
        // Per call. replaceGeofences chains up to four, so a wedged GMS can outlast the receiver's
        // 8s goAsync budget; that join is best-effort.
        val GMS_CALL_TIMEOUT = 5.seconds
    }
}
