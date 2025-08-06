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
    internal val logLevel: CioLogLevel = CioLogLevel.DEFAULT,
    internal val region: Region = Region.US,
    internal val apiHost: String? = null,
    internal val cdnHost: String? = null,
    internal val flushAt: Int = 20,
    internal val flushInterval: Int = 30,
    internal val flushPolicies: List<FlushPolicy> = emptyList(),
    internal val autoAddCustomerIODestination: Boolean = true,
    internal val trackApplicationLifecycleEvents: Boolean = true,
    internal val autoTrackDeviceAttributes: Boolean = true,
    internal val autoTrackActivityScreens: Boolean = false,
    internal val migrationSiteId: String? = null,
    internal val screenViewUse: ScreenView = ScreenView.All,
    internal val modules: List<CustomerIOModule<out CustomerIOModuleConfig>> = emptyList()
)