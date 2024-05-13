package io.customer.datapipelines.extensions

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.sdk.core.di.SDKComponent

internal val SDKComponent.analyticsFactory: ((moduleConfig: DataPipelinesModuleConfig) -> Analytics)?
    get() = getOrNull(identifier = "analyticsFactory")
