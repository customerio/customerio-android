package io.customer.sdk.di

abstract class DiGraph {
    val overrides: MutableMap<String, Any> = mutableMapOf()

    /**
     * Call when you want to override a dependency with a mock in test functions.
     * `di.overrideDependency(CustomerIOInstance::class.java, customerIOMock)`
     *
     * See `CustomerIOTest` (or any other class that is top-level of the module) for an example on use.
     */
    fun <DEP> overrideDependency(dependency: Class<DEP>, value: DEP) {
        overrides[dependency.simpleName] = value as Any
    }

    /**
     * Call in Digraph property getters to get allow mock overriding before trying to get actual instance.
     * ```
     * val fileStorage: FileStorage
     *   get() = override() ?: FileStorage(siteId, context)
     * ```
     * Note: Don't forget to include the interface in the property declaration:
     * ```
     * val foo: InterfaceOfFoo
     *          ^^^^^^^^^^^^^^
     * ```
     * Or you may not be able to successfully mock in test functions.
     */
    inline fun <reified DEP> override(): DEP? = overrides[DEP::class.java.simpleName] as? DEP

    /**
     * We prefer to have all of the SDK's singleton instances held in the dependency injection graph. This makes it easier for automated tests to be able to delete all
     * singletons between each test function and prevent test flakiness.
     */
    val singletons: MutableMap<String, Any> = mutableMapOf()

    /**
     * In the graph, if you have any dependency that should be a singleton:
     * ```
     * val queue: Queue
     *   get() = override() ?: getSingletonInstanceCreate {
     *     QueueImpl(...)
     *   }
     * ```
     */
    inline fun <reified INST : Any> getSingletonInstanceCreate(newInstanceCreator: () -> INST): INST {
        // Use a synchronized block to prevent multiple threads from creating multiple instances of the singleton.
        synchronized(this) {
            val singletonKey = INST::class.java.name

            return singletons[singletonKey] as? INST ?: newInstanceCreator().also {
                singletons[singletonKey] = it
            }
        }
    }

    /**
     * Call to delete instances held by the graph. This is meant to be called in between automated tests but can also be called to reset that state of the SDK at runtime.
     */
    fun reset() {
        overrides.clear()
        singletons.clear()
    }
}
