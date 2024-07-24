package io.customer.datapipelines.extensions

import com.segment.analytics.kotlin.core.Configuration
import com.segment.analytics.kotlin.core.ErrorHandler
import io.customer.datapipelines.config.DataPipelinesModuleConfig

/**
 * Updates analytics configuration using the provided [DataPipelinesModuleConfig].
 */
internal fun updateAnalyticsConfig(
    moduleConfig: DataPipelinesModuleConfig,
    errorHandler: ErrorHandler? = null
): Configuration.() -> Unit = {
    this.flushAt = moduleConfig.flushAt
    this.flushInterval = moduleConfig.flushInterval
    this.flushPolicies = moduleConfig.flushPolicies
    // Force set to false as we don't need to forward events to Segment destination
    // User can disable CIO destination to achieve same results
    this.autoAddSegmentDestination = false
    this.trackApplicationLifecycleEvents = moduleConfig.trackApplicationLifecycleEvents
    this.apiHost = moduleConfig.apiHost
    this.cdnHost = moduleConfig.cdnHost
    this.errorHandler = errorHandler
}
