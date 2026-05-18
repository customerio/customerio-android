package io.customer.location.geofence

import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Trigger-source-aware facade over [GeofenceRepository]. Centralises the decision
 * of whether to launch a refresh (we need a location and the location permissions)
 * and owns the coroutine scope so non-suspending callers (the broadcast receiver,
 * module init) stay simple.
 */
internal interface GeofenceServices {
    fun onMovementTriggerExit(latitude: Double?, longitude: Double?)
    fun onUserIdentified(latitude: Double?, longitude: Double?)
}

internal class GeofenceServicesImpl(
    private val repository: GeofenceRepository,
    private val scope: CoroutineScope,
    private val logger: GeofenceLogger,
    private val permissionChecker: GeofencePermissionChecker
) : GeofenceServices {

    override fun onMovementTriggerExit(latitude: Double?, longitude: Double?) {
        triggerSync(reason = REASON_MOVEMENT_EXIT, latitude = latitude, longitude = longitude)
    }

    override fun onUserIdentified(latitude: Double?, longitude: Double?) {
        triggerSync(reason = REASON_USER_IDENTIFIED, latitude = latitude, longitude = longitude)
    }

    private fun triggerSync(reason: String, latitude: Double?, longitude: Double?) {
        if (latitude == null || longitude == null) {
            logger.logSyncSkippedNoLocation(reason)
            return
        }
        if (!permissionChecker.hasRequiredLocationPermissions()) {
            logger.logSyncSkippedNoPermission(reason)
            return
        }
        logger.logSyncTriggered(reason)
        // Guarded by permissionChecker above; Android kills the process when
        // permissions are revoked, so no mid-flight revocation to handle.
        @SuppressLint("MissingPermission")
        scope.launch {
            repository.refresh(latitude, longitude)
        }
    }

    private companion object {
        const val REASON_MOVEMENT_EXIT = "movement-trigger-exit"
        const val REASON_USER_IDENTIFIED = "user-identified"
    }
}
