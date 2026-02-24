package io.customer.sdk.core.pipeline

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent

/**
 * Thread-safe registry of [ProfileEnrichmentProvider] instances.
 *
 * Modules register providers during initialization. The datapipelines module
 * queries all providers when enriching identify events.
 *
 * Cleared automatically when [SDKComponent.reset] clears singletons.
 *
 * This is an internal SDK contract — not intended for use by host app developers.
 */
@InternalCustomerIOApi
class ProfileEnrichmentRegistry {
    private val providers = mutableListOf<ProfileEnrichmentProvider>()

    @Synchronized
    fun register(provider: ProfileEnrichmentProvider) {
        if (provider !in providers) {
            providers.add(provider)
        }
    }

    @Synchronized
    fun getAll(): List<ProfileEnrichmentProvider> = providers.toList()

    @Synchronized
    fun clear() {
        providers.clear()
    }
}

/**
 * Singleton accessor for [ProfileEnrichmentRegistry] via [SDKComponent].
 */
@InternalCustomerIOApi
val SDKComponent.profileEnrichmentRegistry: ProfileEnrichmentRegistry
    get() = singleton { ProfileEnrichmentRegistry() }
