package io.customer.geofence

import io.customer.sdk.core.module.CustomerIOModuleConfig

/**
 * Geofence module configuration. Currently exposes no tunables; the builder
 * is here so customer code stays stable as geofence-specific options become
 * configurable.
 */
class GeofenceModuleConfig private constructor() : CustomerIOModuleConfig {
    class Builder : CustomerIOModuleConfig.Builder<GeofenceModuleConfig> {
        override fun build(): GeofenceModuleConfig = GeofenceModuleConfig()
    }
}
