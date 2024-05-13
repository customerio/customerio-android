package io.customer.datapipelines.extensions

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.di.SDKComponentKeys
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.registerDependency

/**
 * Registers the analytics factory to the SDKComponent.
 * The method is placed in test sources to avoid exposing it to release builds.
 */
fun SDKComponent.registerAnalyticsFactory(
    factory: (moduleConfig: DataPipelinesModuleConfig) -> Analytics
) = registerDependency(identifier = SDKComponentKeys.AnalyticsFactory) { factory }
