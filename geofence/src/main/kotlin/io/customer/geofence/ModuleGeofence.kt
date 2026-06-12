package io.customer.geofence

import io.customer.sdk.core.module.CustomerIOModule

private const val MODULE_NAME = "Geofence"

/**
 * Geofence module for Customer.io SDK.
 *
 * Currently a scaffold — the actual implementation moves from the location
 * module in the follow-up commit. Customers should not register this module
 * yet; it is exposed to land the new artifact and gradle wiring without
 * mixing it into the move commit.
 */
class ModuleGeofence @JvmOverloads constructor(
    override val moduleConfig: GeofenceModuleConfig = GeofenceModuleConfig.Builder().build()
) : CustomerIOModule<GeofenceModuleConfig> {
    override val moduleName: String = MODULE_NAME

    override fun initialize() {
        // Wiring lands in the follow-up commit that moves geofence code from :location.
    }
}
