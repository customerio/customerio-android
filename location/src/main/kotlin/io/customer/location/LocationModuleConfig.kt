package io.customer.location

import io.customer.sdk.core.module.CustomerIOModuleConfig

/**
 * Location module configurations that can be used to customize
 * location tracking behavior based on the provided configurations.
 */
class LocationModuleConfig private constructor() : CustomerIOModuleConfig {

    class Builder : CustomerIOModuleConfig.Builder<LocationModuleConfig> {
        override fun build(): LocationModuleConfig {
            return LocationModuleConfig()
        }
    }
}
