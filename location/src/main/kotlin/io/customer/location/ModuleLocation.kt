package io.customer.location

import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule

/**
 * Location module for Customer.io SDK.
 *
 * This module provides location tracking capabilities including:
 * - Permission and consent management
 * - One-shot location capture
 * - Continuous location tracking
 */
class ModuleLocation(
    config: LocationModuleConfig
) : CustomerIOModule<LocationModuleConfig> {
    override val moduleName: String = MODULE_NAME
    override val moduleConfig: LocationModuleConfig = config

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
