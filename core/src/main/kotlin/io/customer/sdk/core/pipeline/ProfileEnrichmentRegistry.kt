package io.customer.sdk.core.pipeline

import io.customer.sdk.core.di.SDKComponent

/**
 * Thread-safe registry of [ProfileEnrichmentProvider] instances.
 *
 * Modules register providers during initialization. The datapipelines module
 * queries all providers when enriching identify events.
 *
 * Cleared automatically when [SDKComponent.reset] clears singletons.
 */
class ProfileEnrichmentRegistry {
    private val providers = mutableListOf<ProfileEnrichmentProvider>()

    @Synchronized
    fun register(provider: ProfileEnrichmentProvider) {
        providers.add(provider)
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
val SDKComponent.profileEnrichmentRegistry: ProfileEnrichmentRegistry
    get() = singleton { ProfileEnrichmentRegistry() }
