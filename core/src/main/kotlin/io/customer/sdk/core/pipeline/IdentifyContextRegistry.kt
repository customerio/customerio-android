package io.customer.sdk.core.pipeline

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent

/**
 * Thread-safe registry of [IdentifyContextProvider] instances.
 *
 * Modules register providers during initialization. The datapipelines module
 * queries all providers when enriching identify event context.
 *
 * Cleared automatically when [SDKComponent.reset] clears singletons.
 *
 * This is an internal SDK contract — not intended for use by host app developers.
 */
@InternalCustomerIOApi
class IdentifyContextRegistry {
    private val providers = mutableListOf<IdentifyContextProvider>()

    @Synchronized
    fun register(provider: IdentifyContextProvider) {
        if (provider !in providers) {
            providers.add(provider)
        }
    }

    @Synchronized
    fun getAll(): List<IdentifyContextProvider> = providers.toList()

    @Synchronized
    fun clear() {
        providers.clear()
    }
}

/**
 * Singleton accessor for [IdentifyContextRegistry] via [SDKComponent].
 */
@InternalCustomerIOApi
val SDKComponent.identifyContextRegistry: IdentifyContextRegistry
    get() = singleton { IdentifyContextRegistry() }
