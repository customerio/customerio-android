package io.customer.location

import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule

/**
 * Location module for Customer.io SDK.
 *
 * This module provides location tracking capabilities including:
 * - Manual location setting from host app's existing location system
 * - One-shot SDK-managed location capture
 *
 * Usage:
 * ```
 * val config = CustomerIOConfigBuilder(appContext, "your-api-key")
 *     .addCustomerIOModule(
 *         ModuleLocation(
 *             LocationModuleConfig.Builder()
 *                 .setEnableLocationTracking(true)
 *                 .build()
 *         )
 *     )
 *     .build()
 *
 * CustomerIO.initialize(config)
 * ```
 */
class ModuleLocation @JvmOverloads constructor(
    override val moduleConfig: LocationModuleConfig = LocationModuleConfig.Builder().build()
) : CustomerIOModule<LocationModuleConfig> {
    override val moduleName: String = MODULE_NAME

    override fun initialize() {
        // Module initialization will be implemented in future PRs
    }

    companion object {
        const val MODULE_NAME: String = "Location"

        @JvmStatic
        fun instance(): ModuleLocation {
            return SDKComponent.modules[MODULE_NAME] as? ModuleLocation
                ?: throw IllegalStateException("ModuleLocation not initialized")
        }
    }
}
