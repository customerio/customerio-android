package io.customer.sdk.core.di

import androidx.annotation.CallSuper

abstract class DiGraph {
    /**
     * Map of dependencies that can be overridden with mocks in test functions.
     */
    val overrides: MutableMap<String, Any> = mutableMapOf()

    /**
     * Map of singleton instances of dependencies in the graph.
     */
    val singletons: MutableMap<String, Any> = mutableMapOf()

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
     *   get() = newInstance { LoggerImpl(...) }
     * ```
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
     *   get() = singleton { LoggerImpl(...) }
     * ```
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
    fun <Dependency : Any> overrideDependency(dependency: Class<Dependency>, value: Dependency) {
        overrides[dependency.name] = value as Any
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
    open fun reset() {
        overrides.clear()
        singletons.clear()
    }

    // TODO: Remove deprecated functions after all usages are removed.
    @Deprecated("Use newInstance or singleton instead", ReplaceWith("newInstance()"))
    inline fun <reified DEP : Any> override(): DEP? = overrides[dependencyKey<DEP>(identifier = null)] as? DEP

    @Deprecated("Use singleton instead", ReplaceWith("singleton(newInstanceCreator)"))
    inline fun <reified INST : Any> getSingletonInstanceCreate(newInstanceCreator: () -> INST): INST {
        return getOrCreateSingletonInstance(identifier = null, newInstanceCreator = newInstanceCreator)
    }
}
