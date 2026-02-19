package io.customer.datapipelines.di

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.store.LocationPreferenceStore
import io.customer.datapipelines.store.LocationPreferenceStoreImpl
import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.getOrNull

// Extends the SDKComponent to allow overriding the analytics instance in DataPipelines module
internal val SDKComponent.analyticsFactory: ((moduleConfig: DataPipelinesModuleConfig) -> Analytics)?
    get() = getOrNull(identifier = SDKComponentKeys.AnalyticsFactory)

internal val SDKComponent.dataPipelinesLogger: DataPipelinesLogger
    get() = singleton<DataPipelinesLogger> { DataPipelinesLogger(logger) }

internal val SDKComponent.locationPreferenceStore: LocationPreferenceStore
    get() = singleton<LocationPreferenceStore> {
        LocationPreferenceStoreImpl(android().applicationContext)
    }
