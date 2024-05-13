package io.customer.datapipelines.extensions

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.di.SDKComponentKeys
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.registerDependency

/**
 * Gets the AndroidSDKComponent instance from the SDKComponent object or throw an exception
 * if it is not initialized.
 */
fun SDKComponent.requireAndroidSDKComponent(): AndroidSDKComponent =
    requireNotNull(androidSDKComponent) {
        "AndroidSDKComponent is not initialized. Make sure to call initialize CustomerIO SDK using context before accessing it."
    }

/**
 * Registers the analytics factory to the SDKComponent.
 * The method is placed in test sources to avoid exposing it to release builds.
 */
fun SDKComponent.registerAnalyticsFactory(
    factory: (moduleConfig: DataPipelinesModuleConfig) -> Analytics
) = registerDependency(identifier = SDKComponentKeys.AnalyticsFactory) { factory }
