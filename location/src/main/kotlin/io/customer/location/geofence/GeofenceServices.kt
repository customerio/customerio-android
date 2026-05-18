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
    /**
     * Movement-trigger EXIT bypasses the freshness threshold: the trigger's center
     * must be updated to the new location for the next EXIT to fire correctly, so
     * we always re-fetch regardless of how recent the last sync was.
     */
    fun onMovementTriggerExit(latitude: Double?, longitude: Double?)

    /** Honours the freshness threshold — repeated identify within the window is a no-op. */
    fun onUserIdentified(latitude: Double?, longitude: Double?)

    /**
     * Honours the freshness threshold. Defensive trigger at module init so a
     * previously-identified user gets a sync even when the host app doesn't call
     * identify on this particular launch; the threshold makes it a cheap no-op
     * when identify also fires shortly after.
     */
    fun onAppLaunch(latitude: Double?, longitude: Double?)

    /**
     * Clears all geofence state so a subsequent user doesn't inherit the previous
     * user's geofences. Fired when [Event.UserChangedEvent] arrives with a null userId.
     */
    fun onUserSignedOut()
}

internal class GeofenceServicesImpl(
    private val repository: GeofenceRepository,
    private val scope: CoroutineScope,
    private val logger: GeofenceLogger,
    private val permissionChecker: GeofencePermissionChecker
) : GeofenceServices {

    override fun onMovementTriggerExit(latitude: Double?, longitude: Double?) {
        triggerSync(reason = REASON_MOVEMENT_EXIT, latitude = latitude, longitude = longitude, force = true)
    }

    override fun onUserIdentified(latitude: Double?, longitude: Double?) {
        triggerSync(reason = REASON_USER_IDENTIFIED, latitude = latitude, longitude = longitude, force = false)
    }

    override fun onAppLaunch(latitude: Double?, longitude: Double?) {
        triggerSync(reason = REASON_APP_LAUNCH, latitude = latitude, longitude = longitude, force = false)
    }

    override fun onUserSignedOut() {
        logger.logGeofenceStateResetOnSignOut()
        scope.launch {
            repository.reset()
        }
    }

    private fun triggerSync(reason: String, latitude: Double?, longitude: Double?, force: Boolean) {
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
            repository.refresh(latitude, longitude, force = force)
        }
    }

    private companion object {
        const val REASON_MOVEMENT_EXIT = "movement-trigger-exit"
        const val REASON_USER_IDENTIFIED = "user-identified"
        const val REASON_APP_LAUNCH = "app-launch"
    }
}
