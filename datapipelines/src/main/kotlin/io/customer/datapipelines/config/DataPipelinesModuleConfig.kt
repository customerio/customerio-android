package io.customer.datapipelines.config

import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.datapipelines.extensions.apiHost
import io.customer.datapipelines.extensions.cdnHost
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.data.model.Region

class DataPipelinesModuleConfig(
    // Server key
    val cdpApiKey: String,
    // Host Settings
    region: Region,
    // Optional manual override for apiHost and cdnHost
    apiHostOverride: String? = null,
    cdnHostOverride: String? = null,
    // Dispatching configuration
    val flushAt: Int,
    val flushInterval: Int,
    val flushPolicies: List<FlushPolicy>,
    // Destination configuration
    val autoAddCustomerIODestination: Boolean,
    // Lifecycle tracking
    val trackApplicationLifecycleEvents: Boolean,
    // Track device information
    val autoTrackDeviceAttributes: Boolean,
    // Configuration options required for migration from earlier versions
    val migrationSiteId: String? = null
) : CustomerIOModuleConfig {
    val apiHost: String = apiHostOverride ?: region.apiHost()
    val cdnHost: String = cdnHostOverride ?: region.cdnHost()
}
