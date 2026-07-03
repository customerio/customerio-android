package io.customer.geofence

/**
 * How the geofence module acquires the location it needs. Location acquired for
 * geofencing is never sent to analytics.
 */
enum class GeofenceLocationMode {
    /** The SDK acquires a fix itself when it needs one and none is available. Default. */
    AUTOMATIC,

    /** The host drives it via [ModuleGeofence.refreshFromCurrentLocation]. */
    MANUAL
}
