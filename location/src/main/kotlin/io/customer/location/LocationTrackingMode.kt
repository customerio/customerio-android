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
     * The auto-captured location is cached for identify context enrichment
     * without immediately sending a track event. Manual location APIs
     * ([LocationServices.setLastKnownLocation], [LocationServices.requestLocationUpdate])
     * remain fully functional and will send track events as usual.
     */
    ON_APP_START
}
