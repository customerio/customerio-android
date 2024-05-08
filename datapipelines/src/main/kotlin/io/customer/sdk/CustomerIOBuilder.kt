package io.customer.sdk

import android.app.Application
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.model.Region

/**
 * Creates a new instance of builder for CustomerIO SDK.
 * The class uses builder pattern to simplify the setup and configuration of CustomerIO SDK,
 * including its core components and additional modules.
 * It automatically includes implementations of [DataPipelineInstance] to ensure all events are routed to it.
 *
 * Example usage:
 * ```
 * with(CustomerIOBuilder(appContext: Application context, cdpApiKey = "XXX")) {
 *   setLogLevel(...)
 *   addCustomerIOModule(...)
 *   build()
 * }
 * ```
 */
class CustomerIOBuilder(
    private val applicationContext: Application,
    private val cdpApiKey: String
) {

    private val registeredModules: MutableList<CustomerIOModule<out CustomerIOModuleConfig>> = mutableListOf()

    private var logLevel: CioLogLevel = CioLogLevel.DEFAULT
    private val logger: Logger = SDKComponent.logger

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
     * Specifies the log level for the SDK.
     * Default value is [CioLogLevel.ERROR].
     */
    fun setLogLevel(level: CioLogLevel): CustomerIOBuilder {
        this.logLevel = level
        return this
    }

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

        // Update the log level for the SDK
        SDKComponent.logger.logLevel = logLevel

        // Initialize DataPipelinesModule with the provided configuration
        val dataPipelinesConfig = DataPipelinesModuleConfig(
            cdpApiKey = cdpApiKey,
            region = region,
            apiHostOverride = apiHost,
            cdnHostOverride = cdnHost,
            flushAt = flushAt,
            flushInterval = flushInterval,
            flushPolicies = flushPolicies,
            autoAddCustomerIODestination = autoAddCustomerIODestination,
            trackApplicationLifecycleEvents = trackApplicationLifecycleEvents,
            autoTrackDeviceAttributes = autoTrackDeviceAttributes,
            migrationSiteId = migrationSiteId
        )

        // Initialize CustomerIO instance before initializing the modules
        val customerIO = CustomerIO.createInstance(
            androidSDKComponent = androidSDKComponent,
            moduleConfig = dataPipelinesConfig
        )

        // Register DataPipelines module and all other modules with SDKComponent
        modules[CustomerIO.MODULE_NAME] = customerIO
        modules.putAll(registeredModules.associateBy { module -> module.moduleName })

        modules.forEach { (_, module) ->
            logger.debug("initializing SDK module ${module.moduleName}...")
            module.initialize()
        }

        return customerIO
    }
}
