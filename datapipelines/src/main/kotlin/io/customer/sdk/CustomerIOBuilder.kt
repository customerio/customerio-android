package io.customer.sdk

import android.app.Application
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.datapipelines.config.ScreenView
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Region

/**
 * Creates a new instance of builder for CustomerIO SDK.
 * The class uses builder pattern to simplify the setup and configuration of CustomerIO SDK,
 * including its core components and additional modules.
 * It automatically includes implementations of [DataPipelineInstance] to ensure all events are routed to it.
 *
 * @deprecated Use [CustomerIOConfigBuilder] and [CustomerIO.initialize] instead.
 *
 * Migration example:
 * Old way:
 * ```
 * with(CustomerIOBuilder(appContext: Application context, cdpApiKey = "XXX")) {
 *   setLogLevel(...)
 *   addCustomerIOModule(...)
 *   build()
 * }
 * ```
 *
 * New way:
 * ```
 * val config = CustomerIOConfigBuilder(appContext, "your-api-key")
 *   .logLevel(...)
 *   .addCustomerIOModule(...)
 *   .build()
 *
 * CustomerIO.initialize(config)
 * ```
 */
@Deprecated(
    message = "Use CustomerIOConfigBuilder and CustomerIO.initialize() instead",
    replaceWith = ReplaceWith(
        "CustomerIOConfigBuilder(applicationContext, cdpApiKey).build().let { CustomerIO.initialize(it) }",
        "io.customer.sdk.CustomerIOConfigBuilder",
        "io.customer.sdk.CustomerIO"
    )
)
class CustomerIOBuilder(
    private val applicationContext: Application,
    private val cdpApiKey: String
) {
    // List of modules to be initialized with the SDK
    private val registeredModules: MutableList<CustomerIOModule<out CustomerIOModuleConfig>> = mutableListOf()

    // Logging configuration
    private var logLevel: CioLogLevel = CioLogLevel.DEFAULT

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

    // Track screen views for Activities
    private var autoTrackActivityScreens: Boolean = false

    // Configuration options required for migration from earlier versions
    private var migrationSiteId: String? = null

    // Determines how SDK should handle screen view events
    private var screenViewUse: ScreenView = ScreenView.All

    /**
     * Specifies the log level for the SDK.
     * Default value is [CioLogLevel.ERROR].
     */
    fun logLevel(level: CioLogLevel): CustomerIOBuilder {
        this.logLevel = level
        return this
    }

    /**
     * Specifies the workspace region to ensure CDP requests are routed to the correct regional endpoint.
     * Default values for apiHost and cdnHost are determined by the region.
     * However, if apiHost or cdnHost are manually specified, those values override region-based defaults.
     */
    fun region(region: Region): CustomerIOBuilder {
        this.region = region
        return this
    }

    fun apiHost(apiHost: String): CustomerIOBuilder {
        this.apiHost = apiHost
        return this
    }

    fun cdnHost(cdnHost: String): CustomerIOBuilder {
        this.cdnHost = cdnHost
        return this
    }

    /**
     * Specifies the number of events that should be queued before they are flushed to the server.
     * Default value is 20.
     */
    fun flushAt(flushAt: Int): CustomerIOBuilder {
        this.flushAt = flushAt
        return this
    }

    /**
     * Specifies the interval in seconds at which events should be flushed to the server.
     * Default value is 30 seconds.
     */
    fun flushInterval(flushInterval: Int): CustomerIOBuilder {
        this.flushInterval = flushInterval
        return this
    }

    /**
     * Specifies the list of flush policies that should be applied to the event queue.
     * Default value is an empty list.
     */
    fun flushPolicies(flushPolicies: List<FlushPolicy>): CustomerIOBuilder {
        this.flushPolicies = flushPolicies
        return this
    }

    /**
     * Automatically add Customer.io destination plugin, defaults to `true`
     */
    fun autoAddCustomerIODestination(autoAdd: Boolean): CustomerIOBuilder {
        this.autoAddCustomerIODestination = autoAdd
        return this
    }

    /**
     * Automatically send track for Lifecycle events (eg: Application Opened, Application Backgrounded, etc.), defaults to `true`
     */
    fun trackApplicationLifecycleEvents(track: Boolean): CustomerIOBuilder {
        this.trackApplicationLifecycleEvents = track
        return this
    }

    /**
     * Enable this property if you want SDK to automatic track device attributes such as
     * operating system, device locale, device model, app version etc.
     */
    fun autoTrackDeviceAttributes(track: Boolean): CustomerIOBuilder {
        this.autoTrackDeviceAttributes = track
        return this
    }

    /**
     * Enable this property if you want SDK to automatic track screen views for Activities.
     * Note: This feature is not useful for UI toolkit like Jetpack Compose as it consist of only one Activity and multiple Composable.
     */
    fun autoTrackActivityScreens(track: Boolean): CustomerIOBuilder {
        this.autoTrackActivityScreens = track
        return this
    }

    /**
     * Set the migration site id to migrate the events from the tracking SDK version.
     */
    fun migrationSiteId(migrationSiteId: String?): CustomerIOBuilder {
        this.migrationSiteId = migrationSiteId
        return this
    }

    /**
     * Set the screen view configuration for the SDK.
     *
     * @see ScreenView for more details.
     */
    fun screenViewUse(screenView: ScreenView): CustomerIOBuilder {
        this.screenViewUse = screenView
        return this
    }

    fun <Config : CustomerIOModuleConfig> addCustomerIOModule(module: CustomerIOModule<Config>): CustomerIOBuilder {
        registeredModules.add(module)
        return this
    }

    fun build(): CustomerIO {
        // Create CustomerIOConfig from the current builder state
        val config = CustomerIOConfig(
            applicationContext = applicationContext,
            cdpApiKey = cdpApiKey,
            logLevel = logLevel,
            region = region,
            apiHost = apiHost,
            cdnHost = cdnHost,
            flushAt = flushAt,
            flushInterval = flushInterval,
            flushPolicies = flushPolicies,
            autoAddCustomerIODestination = autoAddCustomerIODestination,
            trackApplicationLifecycleEvents = trackApplicationLifecycleEvents,
            autoTrackDeviceAttributes = autoTrackDeviceAttributes,
            autoTrackActivityScreens = autoTrackActivityScreens,
            migrationSiteId = migrationSiteId,
            screenViewUse = screenViewUse,
            modules = registeredModules.toList()
        )

        // Initialize the SDK and return the instance
        CustomerIO.initialize(config)
        return CustomerIO.instance()
    }
}
