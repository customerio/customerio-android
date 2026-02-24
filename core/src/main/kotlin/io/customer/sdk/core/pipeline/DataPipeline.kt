package io.customer.sdk.core.pipeline

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Abstraction for sending track events to the data pipeline.
 *
 * Modules retrieve an implementation via `SDKComponent.getOrNull<DataPipeline>()`
 * to send events directly without going through EventBus.
 *
 * This is an internal SDK contract — not intended for use by host app developers.
 */
@InternalCustomerIOApi
interface DataPipeline {
    val userId: String?
    fun track(name: String, properties: Map<String, Any?>)
}
