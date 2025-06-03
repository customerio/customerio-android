package io.customer.sdk.core.di

import androidx.annotation.CallSuper
import io.customer.base.internal.InternalCustomerIOApi
import java.util.concurrent.ConcurrentHashMap

abstract class DiGraph {
    /**
     * Map of dependencies that can be overridden with mocks in test functions.
     */
    val overrides = ConcurrentHashMap<String, Any>()

    /**
     * Map of singleton instances of dependencies in the graph.
     */
    val singletons = ConcurrentHashMap<String, Any>()

    /**
     * Internal function to get the key for a dependency in the graph based on its class.
     * If an identifier is provided, it will be used as the key instead of the class name.
     * This function is marked as public only because it is inlined in other public functions.
     */
    inline fun <reified Dependency : Any> dependencyKey(identifier: String?): String {
        return identifier ?: Dependency::class.java.name
    }

    /**
     * Internal function to get an overridden dependency in the graph.
     * This function is marked as public only because it is inlined in other public functions.
     * Do not call this function directly. Instead, use the `newInstance` or `singleton` functions
     * to get the dependency.
     */
    inline fun <reified Dependency : Any> getOverriddenInstance(identifier: String?): Dependency? {
        return overrides[dependencyKey<Dependency>(identifier)] as? Dependency
    }

    /**
     * Internal function to get or create a singleton instance of a dependency in the graph.
     * This function is thread-safe and will only create a single instance of the dependency.
     * This function is marked as public only because it is inlined in other public functions.
     * Do not call this function directly. Instead, use the `singleton` function.
     */
    inline fun <reified Dependency : Any> getOrCreateSingletonInstance(
        identifier: String?,
        newInstanceCreator: () -> Dependency
    ): Dependency {
        // Use a synchronized block to prevent multiple threads from creating multiple instances of the singleton.
        synchronized(lock = singletons) {
            val singletonKey = dependencyKey<Dependency>(identifier)
            return singletons.getOrPut(singletonKey, newInstanceCreator) as Dependency
        }
    }

    /**
     * Create new instance of dependency in the graph every time it is accessed.
     * Example:
     * ```
     * val logger: Logger
     *   get() = newInstance<Logger> { LoggerImpl(...) }
     * ```
     * If the implementation class is different from property type, please make
     * sure to mention class type explicitly so that it can be used for type
     * inference.
     * e.g. use `newInstance<Logger>(...)` instead of `newInstance(...)`.
     */
    inline fun <reified Dependency : Any> newInstance(
        identifier: String? = null,
        newInstanceCreator: () -> Dependency
    ): Dependency = getOverriddenInstance(identifier) ?: newInstanceCreator()

    /**
     * Gets stored instance of dependency in the graph or null if it doesn't exist.
     * Example:
     * ```
     * val logger: Logger?
     *   get() = getOrNull()
     * ```
     */
    inline fun <reified Dependency : Any> getOrNull(identifier: String? = null): Dependency? {
        return getOverriddenInstance(identifier) ?: singletons[dependencyKey<Dependency>(identifier)] as? Dependency
    }

    /**
     * Get or create a singleton instance of dependency in the graph.
     * Example:
     * ```
     * val logger: Logger
     *   get() = singleton<Logger> { LoggerImpl(...) }
     * ```
     * If the implementation class is different from property type, please make
     * sure to mention class type explicitly so that it can be used for type
     * inference.
     * e.g. use `singleton<Logger>(...)` instead of `singleton(...)`.
     */
    inline fun <reified Dependency : Any> singleton(
        identifier: String? = null,
        newInstanceCreator: () -> Dependency
    ): Dependency {
        return getOverriddenInstance(identifier) ?: getOrCreateSingletonInstance(
            identifier = identifier,
            newInstanceCreator = newInstanceCreator
        )
    }

    /**
     * Overrides dependency with mock in test functions.
     * `di.overrideDependency(CustomerIOInstance::class.java, customerIOMock)`
     * See `CustomerIOTest` (or any other class that is top-level of the module) for an example on use.
     */
    inline fun <reified Dependency : Any> overrideDependency(value: Dependency, identifier: String? = null) {
        val key = dependencyKey<Dependency>(identifier)
        overrides[key] = value as Any
    }

    /**
     * Register a dependency in the graph. This is useful for dependencies that
     * are provided later in the lifecycle of the application.
     */
    inline fun <reified Dependency : Any> registerDependency(
        identifier: String? = null,
        newInstanceCreator: () -> Dependency
    ): Dependency = getOrCreateSingletonInstance(
        identifier = identifier,
        newInstanceCreator = newInstanceCreator
    )

    /**
     * Reset the graph to its initial state.
     * This is meant to be called in between automated tests but can also be
     * called to reset that state of the SDK at runtime.
     */
    @CallSuper
    @InternalCustomerIOApi
    open fun reset() {
        synchronized(lock = overrides) {
            overrides.clear()
        }
        synchronized(lock = singletons) {
            singletons.clear()
        }
    }
}
