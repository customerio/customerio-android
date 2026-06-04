package io.customer.location.geofence

import io.customer.sdk.core.util.Logger

/** Structured logger for geofence operations, tagged for logcat filtering. */
internal class GeofenceLogger(private val logger: Logger) {

    fun logGeofencesRegistered(count: Int) {
        logger.debug("Registered $count geofences with OS", tag = TAG)
    }

    fun logBusinessGeofencesKept(count: Int) {
        logger.debug(
            "Kept $count business geofences unchanged in OS; skipped re-upsert to avoid GMS state reconciliation",
            tag = TAG
        )
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
        logger.debug("Geofence '$geofenceId' $transitionName: queued for exactly-once delivery (WorkManager now, analytics pipeline on next foreground)", tag = TAG)
    }

    fun logTransitionSuppressed(geofenceId: String, transitionName: String) {
        logger.debug("Geofence '$geofenceId' $transitionName: suppressed — same transition fired within the cooldown window", tag = TAG)
    }

    fun logTransitionDroppedUnknownId(geofenceId: String) {
        logger.debug("Geofence '$geofenceId' transition dropped — id not in registered store", tag = TAG)
    }

    fun logUnknownTransition(transitionType: Int) {
        logger.debug("Ignoring geofence transition type=$transitionType (only ENTER and EXIT are tracked)", tag = TAG)
    }

    fun logUnknownApiTransitionType(value: String) {
        logger.error("API response contained unknown transition_type='$value' (expected enter/exit). Region's affected types dropped — check SDK / backend version alignment.", tag = TAG)
    }

    fun logTransitionWithoutLocation() {
        logger.debug("Geofence transition fired but OS provided no location; emitting event with lat/lng omitted from properties", tag = TAG)
    }

    fun logMovementTriggerIgnoredNonExit(transitionName: String) {
        logger.debug("Movement trigger geofence fired with transition=$transitionName; only EXIT triggers a sync", tag = TAG)
    }

    fun logSyncTriggered(reason: String) {
        logger.debug("Geofence sync triggered: $reason", tag = TAG)
    }

    fun logSyncSkippedNoLocation(reason: String) {
        logger.debug("Geofence sync skipped ($reason): no location available", tag = TAG)
    }

    fun logSyncSkippedNoPermission(reason: String) {
        logger.debug("Geofence sync skipped ($reason): location permissions not granted", tag = TAG)
    }

    fun logBackgroundDeliveryUnavailable(reason: String) {
        logger.info("Geofence sync ($reason): ACCESS_BACKGROUND_LOCATION not granted — transitions will only fire while the app is in the foreground", tag = TAG)
    }

    fun logGeofenceStateResetOnSignOut() {
        logger.debug("Geofence state reset on user sign-out: clearing persisted regions and OS registrations", tag = TAG)
    }

    fun logSyncSkippedFresh() {
        logger.debug("Geofence sync skipped: last successful sync is still within the freshness window", tag = TAG)
    }

    fun logGeofencingError(errorCode: Int) {
        logger.error("OS reported geofencing error (code=$errorCode); see GeofenceStatusCodes for meaning", tag = TAG)
    }

    fun logSyncFailed(message: String?) {
        logger.error("Geofence sync failed: $message", tag = TAG)
    }

    fun logSyncSucceeded(count: Int) {
        logger.debug("Geofence sync succeeded: $count regions registered", tag = TAG)
    }

    fun logSyncSkipped(reason: String) {
        logger.debug("Geofence sync skipped: $reason", tag = TAG)
    }

    fun logReceiverSkipped(reason: String) {
        logger.debug("Geofence receiver skipped: $reason", tag = TAG)
    }

    fun logEventDeliveryRetryable(geofenceId: String, transitionName: String, message: String?) {
        logger.debug("Geofence '$geofenceId' $transitionName: HTTP delivery hit network error ($message); WorkManager will retry", tag = TAG)
    }

    fun logEventDeliveryFailed(geofenceId: String, transitionName: String, message: String?) {
        logger.error("Geofence '$geofenceId' $transitionName: HTTP delivery failed and will not retry — $message", tag = TAG)
    }

    fun logEventInvalidInput(geofenceId: String?, transitionName: String?) {
        logger.error("Geofence event worker dropped: required field missing (geofenceId='$geofenceId', transition='$transitionName')", tag = TAG)
    }

    fun logEventDeliverySkippedNoUser(geofenceId: String, transitionName: String) {
        logger.debug("Geofence '$geofenceId' $transitionName: HTTP delivery skipped — no identified user", tag = TAG)
    }

    fun logEventDelivered(geofenceId: String, transitionName: String) {
        logger.debug("Geofence '$geofenceId' $transitionName: delivered via WorkManager (direct HTTP); removed from pending store", tag = TAG)
    }

    fun logEventDeliverySkippedAlreadyDelivered(geofenceId: String, transitionName: String) {
        logger.debug("Geofence '$geofenceId' $transitionName: worker skipped — already delivered via the analytics pipeline (claim lost)", tag = TAG)
    }

    fun logForegroundFlushSnapshot(count: Int) {
        logger.debug("Geofence foreground flush: $count pending transition(s) to hand off to the analytics pipeline", tag = TAG)
    }

    fun logForegroundFlushCancelledWorkManager(geofenceId: String, transitionName: String) {
        logger.debug("Geofence '$geofenceId' $transitionName: cancelled pending WorkManager delivery before flush", tag = TAG)
    }

    fun logForegroundFlushPublished(geofenceId: String, transitionName: String) {
        logger.debug("Geofence '$geofenceId' $transitionName: published to analytics pipeline via foreground flush", tag = TAG)
    }

    fun logForegroundFlushEntryFailed(geofenceId: String, transitionName: String, message: String?) {
        logger.error("Geofence '$geofenceId' $transitionName: foreground flush failed; left in store for next flush — $message", tag = TAG)
    }

    fun logForegroundFlushComplete(count: Int) {
        logger.debug("Geofence foreground flush complete: $count transition(s) handed off this run", tag = TAG)
    }

    fun logSchedulerFailed(geofenceId: String, transitionName: String, message: String?) {
        logger.error("Geofence '$geofenceId' $transitionName: WorkManager scheduling failed; EventBus path still attempted — $message", tag = TAG)
    }

    companion object {
        private const val TAG = "Geofence"
    }
}
