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
    val locationMode: GeofenceLocationMode,
    /**
     * How polygons are evaluated after the device approaches their enclosing circle.
     * Default is [PolygonTrackingMode.RESPONSIVE].
     */
    val polygonTrackingMode: PolygonTrackingMode
) : CustomerIOModuleConfig {
    class Builder : CustomerIOModuleConfig.Builder<GeofenceModuleConfig> {
        private var locationMode: GeofenceLocationMode = GeofenceLocationMode.AUTOMATIC
        private var polygonTrackingMode: PolygonTrackingMode = PolygonTrackingMode.RESPONSIVE

        /** Sets how the module acquires location for geofencing. */
        fun setLocationMode(mode: GeofenceLocationMode): Builder {
            this.locationMode = mode
            return this
        }

        /** Sets how polygon geofences acquire the fine-grained fixes used for evaluation. */
        fun setPolygonTrackingMode(mode: PolygonTrackingMode): Builder {
            polygonTrackingMode = mode
            return this
        }

        override fun build(): GeofenceModuleConfig = GeofenceModuleConfig(
            locationMode = locationMode,
            polygonTrackingMode = polygonTrackingMode
        )
    }
}
