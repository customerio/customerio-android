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
    /**
     * Whether a user is currently identified. Updated synchronously on the caller's thread
     * during identify()/clearIdentify(), so it is accurate the instant those calls return —
     * a consumer may gate on it immediately after identify() without racing async propagation.
     */
    val isUserIdentified: Boolean
    fun track(name: String, properties: Map<String, Any?>)
}
