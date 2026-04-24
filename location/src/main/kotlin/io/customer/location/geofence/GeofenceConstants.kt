package io.customer.location.geofence

/** Configurable thresholds and identifiers for geofence monitoring. */
internal object GeofenceConstants {
    const val MOVEMENT_TRIGGER_ID = "cio_movement_trigger"
    const val MOVEMENT_TRIGGER_RADIUS_METERS = 1000f
    const val SERVER_FETCH_DISTANCE_METERS = 5000f
    const val MAX_BUSINESS_GEOFENCES = 19
    const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L
    const val DEDUPE_COOLDOWN_MS = 60 * 60 * 1000L
    const val GEOFENCE_EXPIRATION_NEVER = -1L
    const val PENDING_INTENT_REQUEST_CODE = 0x4765_6F10
}
