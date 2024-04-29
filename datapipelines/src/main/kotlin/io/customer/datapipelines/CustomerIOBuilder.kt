package io.customer.datapipelines

import android.app.Application
import io.customer.android.core.di.SDKComponent
import io.customer.android.core.di.registerAndroidSDKComponent
import io.customer.android.core.module.CustomerIOModule
import io.customer.android.core.module.CustomerIOModuleConfig
import io.customer.android.core.util.Logger
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.sdk.CustomerIO

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
        val androidSDKComponent = SDKComponent.registerAndroidSDKComponent(context = applicationContext)

        // Initialize the DataPipelinesModule with the provided configuration
        val modules = SDKComponent.modules

        val dataPipelinesConfig = DataPipelinesModuleConfig(cdpApiKey = cdpApiKey)
        val dataPipelinesModule = DataPipelinesModule(androidSDKComponent, dataPipelinesConfig)
        modules[DataPipelinesModule.MODULE_NAME] = dataPipelinesModule
        modules.putAll(registeredModules.associateBy { module -> module.moduleName })

        // Initialize the CustomerIO instance before initializing the SDK modules
        val customerIO = CustomerIO(implementation = dataPipelinesModule)
        modules.forEach { (_, module) ->
            logger.debug("initializing SDK module ${module.moduleName}...")
            module.initialize()
        }

        return customerIO
    }
}
