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

    fun logTransitionEmitting(geofenceId: String, transitionName: String) {
        logger.debug("Geofence '$geofenceId' $transitionName: emitting tracked event via WorkManager + EventBus", tag = TAG)
    }

    fun logUnknownTransition(transitionType: Int) {
        logger.debug("Ignoring geofence transition type=$transitionType (only ENTER and EXIT are tracked)", tag = TAG)
    }

    fun logTransitionWithoutLocation() {
        logger.debug("Geofence transition fired but OS provided no location; emitting event with lat/lng omitted from properties", tag = TAG)
    }

    fun logMovementTriggerSkipped() {
        logger.debug("Movement trigger geofence fired; movement-trigger handling lands in MBL-1623", tag = TAG)
    }

    fun logGeofencingError(errorCode: Int) {
        logger.error("OS reported geofencing error (code=$errorCode); see GeofenceStatusCodes for meaning", tag = TAG)
    }

    fun logSyncFailed(message: String?) {
        logger.error("Geofence sync failed: $message", tag = TAG)
    }

    fun logReceiverSkipped(reason: String) {
        logger.debug("Geofence receiver skipped: $reason", tag = TAG)
    }

    fun logEventDeliveryRetryable(geofenceId: String, transitionName: String) {
        logger.debug("Geofence '$geofenceId' $transitionName: HTTP delivery hit network error; WorkManager will retry", tag = TAG)
    }

    fun logEventDeliveryFailed(geofenceId: String, transitionName: String, message: String?) {
        logger.error("Geofence '$geofenceId' $transitionName: HTTP delivery failed and will not retry — $message", tag = TAG)
    }

    fun logEventInvalidInput() {
        logger.error("Geofence event worker dropped: input data missing required fields", tag = TAG)
    }

    companion object {
        private const val TAG = "Geofence"
    }
}
