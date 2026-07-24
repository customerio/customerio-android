package io.customer.geofence

/**
 * How the geofence module operates. Location acquired for geofencing is never
 * sent to analytics.
 */
enum class GeofenceLocationMode {
    /** Geofencing is disabled: applied at initialization, removing any prior OS registrations. */
    OFF,

    /** The SDK acquires a fix itself when it needs one and none is available. Default. */
    AUTOMATIC,

    /** The host drives it via [ModuleGeofence.refreshFromCurrentLocation]. */
    MANUAL
}
