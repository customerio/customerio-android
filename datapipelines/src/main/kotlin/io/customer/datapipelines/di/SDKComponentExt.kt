package io.customer.datapipelines.di

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.getOrNull

internal val SDKComponent.analyticsFactory: ((moduleConfig: DataPipelinesModuleConfig) -> Analytics)?
    get() = getOrNull(identifier = SDKComponentKeys.AnalyticsFactory)
