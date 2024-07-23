package io.customer.datapipelines.testutils.extensions

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.di.SDKComponentKeys
import io.customer.sdk.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.registerDependency
import io.customer.tracking.migration.MigrationProcessor

/**
 * Registers the analytics factory to the SDKComponent.
 * The method is placed in test sources to avoid exposing it to release builds.
 */
fun SDKComponent.registerAnalyticsFactory(
    factory: (moduleConfig: DataPipelinesModuleConfig) -> Analytics
) = registerDependency(identifier = SDKComponentKeys.AnalyticsFactory) { factory }

/**
 * Registers the migration processor factory to the SDKComponent.
 * The method is placed in test sources to avoid exposing it to release builds.
 */
fun SDKComponent.registerMigrationProcessor(
    factory: (dataPipelineInstance: CustomerIO) -> MigrationProcessor
) = registerDependency(identifier = SDKComponentKeys.MigrationProcessor) { factory }
