package io.customer.datapipelines.extensions

import android.content.Context
import com.segment.analytics.kotlin.android.Analytics as AndroidAnalytics
import com.segment.analytics.kotlin.android.AndroidLogger
import com.segment.analytics.kotlin.android.AndroidStorageProvider
import com.segment.analytics.kotlin.android.plugins.AndroidContextPlugin
import com.segment.analytics.kotlin.android.plugins.AndroidLifecyclePlugin
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Configuration
import com.segment.analytics.kotlin.core.CoroutineConfiguration
import com.segment.analytics.kotlin.core.ErrorHandler
import com.segment.analytics.kotlin.core.Settings
import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.di.segmentCoroutineConfiguration
import io.customer.datapipelines.di.segmentRequestFactory
import io.customer.datapipelines.network.UserJwtRequestFactory
import io.customer.datapipelines.plugins.CUSTOMER_IO_DATA_PIPELINES
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.identity.userJwtStore
import kotlinx.serialization.json.buildJsonObject

/**
 * Updates analytics configuration using the provided [DataPipelinesModuleConfig].
 */
@OptIn(InternalCustomerIOApi::class)
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
    this.defaultSettings = this.defaultSettings?.copy(
        integrations = buildJsonObject {
            put(CUSTOMER_IO_DATA_PIPELINES, emptyJsonObject)
        }
    ) ?: Settings(
        integrations = buildJsonObject {
            put(CUSTOMER_IO_DATA_PIPELINES, emptyJsonObject)
        }
    )
    // Lifecycle events are tracked by AutomaticApplicationLifecycleTrackingPlugin instead, which also works when the
    // SDK is initialized after the first Activity. Segment's lifecycle plugin still forwards Activity callbacks to
    // other plugins with this off.
    this.trackApplicationLifecycleEvents = false
    this.apiHost = moduleConfig.apiHost
    this.cdnHost = moduleConfig.cdnHost
    this.errorHandler = errorHandler
    // Tests only: answer Segment's requests without a network
    this.requestFactory = SDKComponent.segmentRequestFactory
        ?: UserJwtRequestFactory { SDKComponent.userJwtStore.current?.token }
}

/**
 * Segment's Android [Analytics], as `Analytics(writeKey, context, configs)` from Segment's Android
 * library builds it. Tests only: with a [CoroutineConfiguration] registered
 * ([SDKComponent.segmentCoroutineConfiguration]), Segment runs its work on the threads it gives.
 */
internal fun createAnalytics(
    writeKey: String,
    context: Context,
    configs: Configuration.() -> Unit
): Analytics {
    val coroutineConfig = SDKComponent.segmentCoroutineConfiguration?.invoke()
        ?: return AndroidAnalytics(writeKey = writeKey, context = context, configs = configs)
    Analytics.setLogger(AndroidLogger())
    val configuration = Configuration(writeKey = writeKey, application = context, storageProvider = AndroidStorageProvider)
    configs.invoke(configuration)
    return ConfiguredAnalytics(configuration, coroutineConfig).apply {
        // What Segment's Android builder adds once built (its `startup()` is private). Left out: its
        // settings re-check each time the app comes to the foreground
        add(AndroidContextPlugin())
        add(AndroidLifecyclePlugin())
    }
}

/** Reaches Segment's constructor that takes a [CoroutineConfiguration]. */
private class ConfiguredAnalytics(
    configuration: Configuration,
    coroutineConfig: CoroutineConfiguration
) : Analytics(configuration, coroutineConfig)
