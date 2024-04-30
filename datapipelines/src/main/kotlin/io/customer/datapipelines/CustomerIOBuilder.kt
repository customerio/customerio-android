package io.customer.datapipelines

import android.app.Application
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.model.Region

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

    // Host Settings
    private var region: Region = Region.US
    private var apiHost: String? = null
    private var cdnHost: String? = null

    // Dispatching configuration
    private var flushAt: Int = 20
    private var flushInterval: Int = 30
    private var flushPolicies: List<FlushPolicy> = emptyList()

    // Destination configuration
    private var autoAddCustomerIODestination: Boolean = true

    // Lifecycle tracking
    private var trackApplicationLifecycleEvents: Boolean = true

    // Track device information
    private var autoTrackDeviceAttributes: Boolean = true

    // Configuration options required for migration from earlier versions
    private var migrationSiteId: String? = null

    /**
     * Specifies the workspace region to ensure CDP requests are routed to the correct regional endpoint.
     * Default values for apiHost and cdnHost are determined by the region.
     * However, if apiHost or cdnHost are manually specified, those values override region-based defaults.
     */
    fun setRegion(region: Region): CustomerIOBuilder {
        this.region = region
        return this
    }

    fun setApiHost(apiHost: String): CustomerIOBuilder {
        this.apiHost = apiHost
        return this
    }

    fun setCdnHost(cdnHost: String): CustomerIOBuilder {
        this.cdnHost = cdnHost
        return this
    }

    /**
     * Specifies the number of events that should be queued before they are flushed to the server.
     * Default value is 20.
     */
    fun setFlushAt(flushAt: Int): CustomerIOBuilder {
        this.flushAt = flushAt
        return this
    }

    /**
     * Specifies the interval in seconds at which events should be flushed to the server.
     * Default value is 30 seconds.
     */
    fun setFlushInterval(flushInterval: Int): CustomerIOBuilder {
        this.flushInterval = flushInterval
        return this
    }

    /**
     * Specifies the list of flush policies that should be applied to the event queue.
     * Default value is an empty list.
     */
    fun setFlushPolicies(flushPolicies: List<FlushPolicy>): CustomerIOBuilder {
        this.flushPolicies = flushPolicies
        return this
    }

    /**
     * Automatically add Customer.io destination plugin, defaults to `true`
     */
    fun setAutoAddCustomerIODestination(autoAdd: Boolean): CustomerIOBuilder {
        this.autoAddCustomerIODestination = autoAdd
        return this
    }

    /**
     * Automatically send track for Lifecycle events (eg: Application Opened, Application Backgrounded, etc.), defaults to `true`
     */
    fun setTrackApplicationLifecycleEvents(track: Boolean): CustomerIOBuilder {
        this.trackApplicationLifecycleEvents = track
        return this
    }

    /**
     * Enable this property if you want SDK to automatic track device attributes such as
     * operating system, device locale, device model, app version etc.
     */
    fun setAutoTrackDeviceAttributes(track: Boolean): CustomerIOBuilder {
        this.autoTrackDeviceAttributes = track
        return this
    }

    /**
     * Set the migration site id to migrate the events from the tracking SDK version.
     */
    fun setMigrationSiteId(migrationSiteId: String?): CustomerIOBuilder {
        this.migrationSiteId = migrationSiteId
        return this
    }

    fun <Config : CustomerIOModuleConfig> addCustomerIOModule(module: CustomerIOModule<Config>): CustomerIOBuilder {
        registeredModules.add(module)
        return this
    }

    fun build(): CustomerIO {
        // Register AndroidSDKComponent to fulfill the dependencies required by the SDK modules
        val androidSDKComponent = SDKComponent.registerAndroidSDKComponent(context = applicationContext)
        val modules = SDKComponent.modules

        // Initialize DataPipelinesModule with the provided configuration
        val dataPipelinesConfig = DataPipelinesModuleConfig(
            cdpApiKey = cdpApiKey, region = region, apiHost = apiHost, cdnHost = cdnHost, flushAt = flushAt, flushInterval = flushInterval, flushPolicies = flushPolicies, autoAddCustomerIODestination = autoAddCustomerIODestination, trackApplicationLifecycleEvents = trackApplicationLifecycleEvents, autoTrackDeviceAttributes = autoTrackDeviceAttributes, migrationSiteId = migrationSiteId
        )
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
