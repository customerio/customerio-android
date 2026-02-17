package io.customer.location

import io.customer.sdk.core.module.CustomerIOModuleConfig

/**
 * Location module configurations that can be used to customize
 * location tracking behavior based on the provided configurations.
 */
class LocationModuleConfig private constructor(
    /**
     * Whether location tracking is enabled.
     *
     * When false, the location module is effectively disabled and all location
     * tracking operations will no-op silently.
     */
    val enableLocationTracking: Boolean
) : CustomerIOModuleConfig {

    class Builder : CustomerIOModuleConfig.Builder<LocationModuleConfig> {
        private var enableLocationTracking: Boolean = true

        /**
         * Sets whether location tracking is enabled.
         * When disabled, all location operations will no-op silently.
         * Default is true.
         */
        fun setEnableLocationTracking(enable: Boolean): Builder {
            this.enableLocationTracking = enable
            return this
        }

        override fun build(): LocationModuleConfig {
            return LocationModuleConfig(
                enableLocationTracking = enableLocationTracking
            )
        }
    }
}
