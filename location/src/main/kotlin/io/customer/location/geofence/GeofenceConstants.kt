package io.customer.location.geofence

/**
 * Constants for geofencing functionality.
 */
internal object GeofenceConstants {
    /**
     * Event names sent to Customer.io for geofence transitions.
     */
    const val EVENT_GEOFENCE_ENTERED = "Geofence Entered"
    const val EVENT_GEOFENCE_EXITED = "Geofence Exited"
    const val EVENT_GEOFENCE_DWELLED = "Geofence Dwelled"

    /**
     * Default dwell time in milliseconds (10 minutes).
     */
    const val DEFAULT_DWELL_TIME_MS = 10 * 60 * 1000L

    /**
     * Default geofence expiration (never expires).
     */
    const val GEOFENCE_EXPIRATION_NEVER = -1L

    /**
     * Default geofence radius in meters if not specified.
     */
    const val DEFAULT_RADIUS_METERS = 100.0

    /**
     * Action for geofence transition broadcast.
     */
    const val GEOFENCE_TRANSITION_ACTION = "io.customer.location.GEOFENCE_TRANSITION"

    /**
     * Preference store key for persisted geofences.
     */
    const val PREF_KEY_GEOFENCES = "io.customer.location.geofences"
}
