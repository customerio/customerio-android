package io.customer.sdk.core.pipeline

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent

/**
 * Thread-safe registry of [IdentifyHook] instances.
 *
 * Modules register hooks during initialization. The datapipelines module
 * queries all hooks when enriching identify event context and on reset.
 *
 * Cleared automatically when [SDKComponent.reset] clears singletons.
 *
 * This is an internal SDK contract — not intended for use by host app developers.
 */
@InternalCustomerIOApi
class IdentifyHookRegistry {
    private val hooks = mutableListOf<IdentifyHook>()

    @Synchronized
    fun register(hook: IdentifyHook) {
        if (hook !in hooks) {
            hooks.add(hook)
        }
    }

    @Synchronized
    fun getAll(): List<IdentifyHook> = hooks.toList()

    @Synchronized
    fun clear() {
        hooks.clear()
    }
}

/**
 * Singleton accessor for [IdentifyHookRegistry] via [SDKComponent].
 */
@InternalCustomerIOApi
val SDKComponent.identifyHookRegistry: IdentifyHookRegistry
    get() = singleton { IdentifyHookRegistry() }
