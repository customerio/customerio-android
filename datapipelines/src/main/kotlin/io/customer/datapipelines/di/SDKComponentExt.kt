package io.customer.datapipelines.di

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.sdk.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.getOrNull
import io.customer.tracking.migration.MigrationProcessor

// Extends the SDKComponent to allow overriding the analytics instance in DataPipelines module
internal val SDKComponent.analyticsFactory: ((moduleConfig: DataPipelinesModuleConfig) -> Analytics)?
    get() = getOrNull(identifier = SDKComponentKeys.AnalyticsFactory)

// Extends the SDKComponent to allow overriding the migration processor in DataPipelines module
internal val SDKComponent.migrationProcessor: ((dataPipelineInstance: CustomerIO) -> MigrationProcessor)?
    get() = getOrNull(identifier = SDKComponentKeys.MigrationProcessor)
