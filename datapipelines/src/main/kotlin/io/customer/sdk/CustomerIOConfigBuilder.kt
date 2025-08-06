package io.customer.sdk

import android.app.Application
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.datapipelines.config.ScreenView
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Region

/**
 * Builder class for creating CustomerIO SDK configuration.
 * This class uses the builder pattern to simplify the setup and configuration of CustomerIO SDK.
 *
 * Example usage:
 * ```
 * val config = CustomerIOConfigBuilder(appContext, "your-api-key")
 *     .logLevel(CioLogLevel.DEBUG)
 *     .region(Region.EU)
 *     .build()
 *
 * CustomerIO.initialize(config)
 * ```
 */
class CustomerIOConfigBuilder(
    private val applicationContext: Application,
    private val cdpApiKey: String
) {
    /**
     * Default values for CustomerIO SDK configuration.
     * These defaults are used by both CustomerIOConfigBuilder and the deprecated CustomerIOBuilder.
     */
    internal companion object Defaults {
        val logLevel: CioLogLevel = CioLogLevel.DEFAULT
        val region: Region = Region.US
        val apiHost: String? = null
        val cdnHost: String? = null
        val flushAt: Int = 20
        val flushInterval: Int = 30
        val flushPolicies: List<FlushPolicy> = emptyList()
        val autoAddCustomerIODestination: Boolean = true
        val trackApplicationLifecycleEvents: Boolean = true
        val autoTrackDeviceAttributes: Boolean = true
        val autoTrackActivityScreens: Boolean = false
        val migrationSiteId: String? = null
        val screenViewUse: ScreenView = ScreenView.All
        val modules: List<CustomerIOModule<out CustomerIOModuleConfig>> = emptyList()
    }

    // Logging configuration
    private var logLevel: CioLogLevel = Defaults.logLevel

    // Host Settings
    private var region: Region = Defaults.region
    private var apiHost: String? = Defaults.apiHost
    private var cdnHost: String? = Defaults.cdnHost

    // Dispatching configuration
    private var flushAt: Int = Defaults.flushAt
    private var flushInterval: Int = Defaults.flushInterval
    private var flushPolicies: List<FlushPolicy> = Defaults.flushPolicies

    // Destination configuration
    private var autoAddCustomerIODestination: Boolean = Defaults.autoAddCustomerIODestination

    // Lifecycle tracking
    private var trackApplicationLifecycleEvents: Boolean = Defaults.trackApplicationLifecycleEvents

    // Track device information
    private var autoTrackDeviceAttributes: Boolean = Defaults.autoTrackDeviceAttributes

    // Track screen views for Activities
    private var autoTrackActivityScreens: Boolean = Defaults.autoTrackActivityScreens

    // Configuration options required for migration from earlier versions
    private var migrationSiteId: String? = Defaults.migrationSiteId

    // Determines how SDK should handle screen view events
    private var screenViewUse: ScreenView = Defaults.screenViewUse

    // List of modules to be initialized with the SDK
    private val modules: MutableList<CustomerIOModule<out CustomerIOModuleConfig>> = mutableListOf()

    /**
     * Specifies the log level for the SDK.
     * Default value is [CioLogLevel.DEFAULT].
     */
    fun logLevel(level: CioLogLevel): CustomerIOConfigBuilder {
        this.logLevel = level
        return this
    }

    /**
     * Specifies the workspace region to ensure CDP requests are routed to the correct regional endpoint.
     * Default value is [Region.US].
     * Default values for apiHost and cdnHost are determined by the region.
     * However, if apiHost or cdnHost are manually specified, those values override region-based defaults.
     */
    fun region(region: Region): CustomerIOConfigBuilder {
        this.region = region
        return this
    }

    fun apiHost(apiHost: String): CustomerIOConfigBuilder {
        this.apiHost = apiHost
        return this
    }

    fun cdnHost(cdnHost: String): CustomerIOConfigBuilder {
        this.cdnHost = cdnHost
        return this
    }

    /**
     * Specifies the number of events that should be queued before they are flushed to the server.
     * Default value is 20.
     */
    fun flushAt(flushAt: Int): CustomerIOConfigBuilder {
        this.flushAt = flushAt
        return this
    }

    /**
     * Specifies the interval in seconds at which events should be flushed to the server.
     * Default value is 30 seconds.
     */
    fun flushInterval(flushInterval: Int): CustomerIOConfigBuilder {
        this.flushInterval = flushInterval
        return this
    }

    /**
     * Specifies the list of flush policies that should be applied to the event queue.
     * Default value is an empty list.
     */
    fun flushPolicies(flushPolicies: List<FlushPolicy>): CustomerIOConfigBuilder {
        this.flushPolicies = flushPolicies
        return this
    }

    /**
     * Automatically add Customer.io destination plugin, defaults to `true`
     */
    fun autoAddCustomerIODestination(autoAdd: Boolean): CustomerIOConfigBuilder {
        this.autoAddCustomerIODestination = autoAdd
        return this
    }

    /**
     * Automatically send track for Lifecycle events (eg: Application Opened, Application Backgrounded, etc.), defaults to `true`
     */
    fun trackApplicationLifecycleEvents(track: Boolean): CustomerIOConfigBuilder {
        this.trackApplicationLifecycleEvents = track
        return this
    }

    /**
     * Enable this property if you want SDK to automatic track device attributes such as
     * operating system, device locale, device model, app version etc.
     * Default value is `true`.
     */
    fun autoTrackDeviceAttributes(track: Boolean): CustomerIOConfigBuilder {
        this.autoTrackDeviceAttributes = track
        return this
    }

    /**
     * Enable this property if you want SDK to automatic track screen views for Activities.
     * Note: This feature is not useful for UI toolkit like Jetpack Compose as it consist of only one Activity and multiple Composable.
     * Default value is `false`.
     */
    fun autoTrackActivityScreens(track: Boolean): CustomerIOConfigBuilder {
        this.autoTrackActivityScreens = track
        return this
    }

    /**
     * Set the migration site id to migrate the events from the tracking SDK version.
     * Default value is `null`.
     */
    fun migrationSiteId(migrationSiteId: String?): CustomerIOConfigBuilder {
        this.migrationSiteId = migrationSiteId
        return this
    }

    /**
     * Set the screen view configuration for the SDK.
     * Default value is [ScreenView.All].
     *
     * @see ScreenView for more details.
     */
    fun screenViewUse(screenView: ScreenView): CustomerIOConfigBuilder {
        this.screenViewUse = screenView
        return this
    }

    /**
     * Add a CustomerIO module to be initialized with the SDK.
     */
    fun <Config : CustomerIOModuleConfig> addCustomerIOModule(module: CustomerIOModule<Config>): CustomerIOConfigBuilder {
        modules.add(module)
        return this
    }

    /**
     * Build the CustomerIOConfig instance with the specified configuration.
     */
    fun build(): CustomerIOConfig {
        return CustomerIOConfig(
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
            modules = modules.toList()
        )
    }
}
