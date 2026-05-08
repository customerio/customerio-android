package io.customer.location.geofence

import io.customer.sdk.core.util.Logger

/** Structured logger for geofence operations, tagged for logcat filtering. */
internal class GeofenceLogger(private val logger: Logger) {

    fun logGeofencesRegistered(count: Int) {
        logger.debug("Registered $count geofences with OS", tag = TAG)
    }

    fun logGeofencesRemoved(count: Int) {
        logger.debug("Removed $count geofences from OS", tag = TAG)
    }

    fun logGeofencesCleared() {
        logger.debug("Cleared all geofences from OS", tag = TAG)
    }

    fun logRegistrationFailed(message: String?) {
        logger.error("Failed to register geofences: $message", tag = TAG)
    }

    fun logRemovalFailed(message: String?) {
        logger.error("Failed to remove geofences: $message", tag = TAG)
    }

    fun logMissingPermission(permission: String) {
        logger.error("Cannot register geofences: $permission not granted. Host app must request this permission.", tag = TAG)
    }

    fun logTransitionReceived(geofenceId: String, transitionName: String) {
        logger.debug("Transition $transitionName for geofence $geofenceId", tag = TAG)
    }

    fun logGeofencingError(errorCode: Int) {
        logger.error("Geofencing error code: $errorCode", tag = TAG)
    }

    fun logSyncFailed(message: String?) {
        logger.error("Geofence sync failed: $message", tag = TAG)
    }

    fun logReceiverSkipped(reason: String) {
        logger.debug("Geofence receiver skipped: $reason", tag = TAG)
    }

    companion object {
        private const val TAG = "Geofence"
    }
}
