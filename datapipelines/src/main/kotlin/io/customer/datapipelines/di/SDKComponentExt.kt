package io.customer.datapipelines.di

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.getOrNull

// Extends the SDKComponent to allow overriding the analytics instance in DataPipelines module
internal val SDKComponent.analyticsFactory: ((moduleConfig: DataPipelinesModuleConfig) -> Analytics)?
    get() = getOrNull(identifier = SDKComponentKeys.AnalyticsFactory)
