package io.customer.sdk.core.pipeline

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Interface for modules that contribute attributes to identify events.
 *
 * Implementations return a map of primitive-valued attributes (String, Number, Boolean)
 * that will be added to the identify event context. Return null to skip enrichment.
 *
 * This is an internal SDK contract — not intended for use by host app developers.
 */
@InternalCustomerIOApi
interface ProfileEnrichmentProvider {
    fun getProfileEnrichmentAttributes(): Map<String, Any>?
}
