package io.customer.location

import io.customer.sdk.core.module.CustomerIOModuleConfig

/**
 * Location module configurations that can be used to customize
 * location tracking behavior based on the provided configurations.
 */
class LocationModuleConfig private constructor(
    /**
     * The tracking mode for the location module.
     *
     * - [LocationTrackingMode.OFF]: Location tracking is disabled; all operations no-op.
     * - [LocationTrackingMode.MANUAL]: Host app controls when location is captured (default).
     * - [LocationTrackingMode.ON_APP_START]: SDK auto-captures location on cold start,
     *   caching it for identify context enrichment.
     */
    val trackingMode: LocationTrackingMode
) : CustomerIOModuleConfig {

    /**
     * Whether location tracking is enabled (any mode other than [LocationTrackingMode.OFF]).
     */
    internal val isEnabled: Boolean
        get() = trackingMode != LocationTrackingMode.OFF

    class Builder : CustomerIOModuleConfig.Builder<LocationModuleConfig> {
        private var trackingMode: LocationTrackingMode = LocationTrackingMode.MANUAL

        /**
         * Sets the location tracking mode.
         * Default is [LocationTrackingMode.MANUAL].
         */
        fun setLocationTrackingMode(mode: LocationTrackingMode): Builder {
            this.trackingMode = mode
            return this
        }

        override fun build(): LocationModuleConfig {
            return LocationModuleConfig(
                trackingMode = trackingMode
            )
        }
    }
}
