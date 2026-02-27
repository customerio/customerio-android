package io.customer.sdk.core.pipeline

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Hook for modules that participate in the identify event lifecycle.
 *
 * [getIdentifyContext] returns context entries (String, Number, Boolean)
 * added to the identify event's context via `putInContext()`. Return an
 * empty map when there is nothing to contribute. These are context-level
 * enrichment data (e.g., location coordinates), NOT profile traits.
 *
 * [resetContext] is called synchronously during `analytics.reset()`
 * (clearIdentify flow). Implementations must clear any cached data
 * here to prevent stale context from enriching a subsequent identify.
 * Full cleanup (persistence, filters) can happen asynchronously via
 * EventBus ResetEvent.
 *
 * This is an internal SDK contract — not intended for use by host app developers.
 */
@InternalCustomerIOApi
interface IdentifyHook {
    fun getIdentifyContext(): Map<String, Any>
    fun resetContext() {}
}
