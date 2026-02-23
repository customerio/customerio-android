package io.customer.sdk.core.pipeline

/**
 * Interface for modules that contribute attributes to identify events.
 *
 * Implementations return a map of primitive-valued attributes (String, Number, Boolean)
 * that will be added to the identify event context. Return null to skip enrichment.
 */
interface ProfileEnrichmentProvider {
    fun getProfileEnrichmentAttributes(): Map<String, Any>?
}
