package io.customer.sdk.core.pipeline

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Interface for modules that contribute context entries to identify events.
 *
 * Implementations return a map of primitive-valued entries (String, Number, Boolean)
 * that will be added to the identify event's context via `putInContext()`.
 * Return an empty map when there is nothing to contribute.
 *
 * These are NOT profile traits/attributes — they are context-level enrichment
 * data (e.g., location coordinates) attached to the identify event payload.
 *
 * This is an internal SDK contract — not intended for use by host app developers.
 */
@InternalCustomerIOApi
interface IdentifyContextProvider {
    fun getIdentifyContext(): Map<String, Any>
}
