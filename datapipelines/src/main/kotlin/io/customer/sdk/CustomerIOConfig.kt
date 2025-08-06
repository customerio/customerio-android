package io.customer.sdk

import android.app.Application
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.datapipelines.config.ScreenView
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Region

/**
 * Configuration class for initializing CustomerIO SDK.
 * Contains all the necessary configuration options for setting up the SDK.
 */
data class CustomerIOConfig(
    internal val applicationContext: Application,
    internal val cdpApiKey: String,
    internal val logLevel: CioLogLevel,
    internal val region: Region,
    internal val apiHost: String?,
    internal val cdnHost: String?,
    internal val flushAt: Int,
    internal val flushInterval: Int,
    internal val flushPolicies: List<FlushPolicy>,
    internal val autoAddCustomerIODestination: Boolean,
    internal val trackApplicationLifecycleEvents: Boolean,
    internal val autoTrackDeviceAttributes: Boolean,
    internal val autoTrackActivityScreens: Boolean,
    internal val migrationSiteId: String?,
    internal val screenViewUse: ScreenView,
    internal val modules: List<CustomerIOModule<out CustomerIOModuleConfig>>
)
