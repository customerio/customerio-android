package io.customer.geofence

import io.customer.sdk.core.module.CustomerIOModuleConfig

/**
 * Geofence module configuration.
 */
class GeofenceModuleConfig private constructor(
    /**
     * How the module acquires the device location it needs for geofencing.
     * Default is [GeofenceLocationMode.AUTOMATIC].
     */
    val locationMode: GeofenceLocationMode
) : CustomerIOModuleConfig {
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
