package io.customer.datapipelines

import android.app.Application
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.Logger

/**
 * Builder class for creating a new instance of CustomerIO.
 * The class uses builder pattern to simplify the setup and configuration of CustomerIO SDK,
 * including its core components and additional modules.
 * It automatically includes the [DataPipelinesModule] to ensure all events are routed to it.
 */
class CustomerIOBuilder internal constructor(
    private val applicationContext: Application,
    private val cdpApiKey: String
) {
    private val logger: Logger = SDKComponent.logger
    private val registeredModules: MutableList<CustomerIOModule<out CustomerIOModuleConfig>> = mutableListOf()

    fun <Config : CustomerIOModuleConfig> addCustomerIOModule(module: CustomerIOModule<Config>): CustomerIOBuilder {
        registeredModules.add(module)
        return this
    }

    fun build(): CustomerIO {
        // Register AndroidSDKComponent to fulfill the dependencies required by the SDK modules
        val androidSDKComponent = SDKComponent.registerAndroidSDKComponent(context = applicationContext)
        val modules = SDKComponent.modules

        // Initialize DataPipelinesModule with the provided configuration
        val dataPipelinesConfig = DataPipelinesModuleConfig(cdpApiKey = cdpApiKey)
        val dataPipelinesModule = DataPipelinesModule(androidSDKComponent, dataPipelinesConfig)

        // Register DataPipelinesModule and all other modules
        modules[DataPipelinesModule.MODULE_NAME] = dataPipelinesModule
        modules.putAll(registeredModules.associateBy { module -> module.moduleName })

        // Initialize CustomerIO instance before initializing the modules
        val customerIO = CustomerIO.createInstance(implementation = dataPipelinesModule)
        modules.forEach { (_, module) ->
            logger.debug("initializing SDK module ${module.moduleName}...")
            module.initialize()
        }

        return customerIO
    }
}
