package io.customer.datapipelines.di

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.CoroutineConfiguration
import com.segment.analytics.kotlin.core.RequestFactory
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.handoff.SegmentGateway
import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.getOrNull
import io.customer.sdk.core.extensions.registerDependency

// Extends the SDKComponent to allow overriding the analytics instance in DataPipelines module
internal val SDKComponent.analyticsFactory: ((moduleConfig: DataPipelinesModuleConfig) -> Analytics)?
    get() = getOrNull(identifier = SDKComponentKeys.AnalyticsFactory)

// Extends the SDKComponent to allow wrapping the Segment seam in tests, e.g. to break hand-offs on purpose
internal val SDKComponent.segmentGatewayFactory: ((analytics: Analytics) -> SegmentGateway)?
    get() = getOrNull(identifier = SDKComponentKeys.SegmentGatewayFactory)

// Extends the SDKComponent to allow answering Segment's requests in tests, without a network
internal val SDKComponent.segmentRequestFactory: RequestFactory?
    get() = getOrNull(identifier = SDKComponentKeys.SegmentRequestFactory)

/**
 * Test-only: Segment builds its requests with [factory] in every Analytics instance created from now
 * until [SDKComponent] is reset. The replay suite's parity mode answers them in memory.
 */
@InternalCustomerIOApi
fun SDKComponent.registerSegmentRequestFactory(factory: RequestFactory) {
    registerDependency(identifier = SDKComponentKeys.SegmentRequestFactory) { factory }
}

// Extends the SDKComponent to allow running Segment on threads a test controls
internal val SDKComponent.segmentCoroutineConfiguration: (() -> CoroutineConfiguration)?
    get() = getOrNull(identifier = SDKComponentKeys.SegmentCoroutineConfiguration)

/**
 * Test-only: every Analytics instance created from now until [SDKComponent] is reset runs its work in
 * the scope and on the dispatchers [factory] gives it (one call per instance). The replay suite
 * controls when Segment applies a call with it.
 */
@InternalCustomerIOApi
fun SDKComponent.registerSegmentCoroutineConfiguration(factory: () -> CoroutineConfiguration) {
    registerDependency(identifier = SDKComponentKeys.SegmentCoroutineConfiguration) { factory }
}

internal val SDKComponent.dataPipelinesLogger: DataPipelinesLogger
    get() = singleton<DataPipelinesLogger> { DataPipelinesLogger(logger) }
