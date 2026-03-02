package io.customer.location

/**
 * Defines the tracking mode for the location module.
 */
enum class LocationTrackingMode {
    /**
     * Location tracking is disabled.
     * All location operations will no-op silently.
     */
    OFF,

    /**
     * Host app controls when location is captured.
     * Use [LocationServices.setLastKnownLocation] or [LocationServices.requestLocationUpdate]
     * to manually trigger location capture. This is the default mode.
     */
    MANUAL,

    /**
     * SDK automatically captures location on cold start.
     *
     * The captured location goes through the normal sync path
     * (cache + 24h/1km filter + track event if filter passes).
     * Manual location APIs remain fully functional.
     */
    ON_APP_START
}
