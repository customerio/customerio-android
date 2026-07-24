package io.customer.geofence

import io.customer.sdk.core.module.CustomerIOModuleConfig

/**
 * Geofence module configuration.
 */
class GeofenceModuleConfig private constructor(
    /** How the module operates. Default is [GeofenceLocationMode.AUTOMATIC]. */
    val locationMode: GeofenceLocationMode
) : CustomerIOModuleConfig {

    /** Whether geofencing is enabled (any mode other than [GeofenceLocationMode.OFF]). */
    internal val isEnabled: Boolean
        get() = locationMode != GeofenceLocationMode.OFF

    class Builder : CustomerIOModuleConfig.Builder<GeofenceModuleConfig> {
        private var locationMode: GeofenceLocationMode = GeofenceLocationMode.AUTOMATIC

        /** Sets how the module acquires location for geofencing. */
        fun setLocationMode(mode: GeofenceLocationMode): Builder {
            this.locationMode = mode
            return this
        }

        override fun build(): GeofenceModuleConfig = GeofenceModuleConfig(
            locationMode = locationMode
        )
    }
}
