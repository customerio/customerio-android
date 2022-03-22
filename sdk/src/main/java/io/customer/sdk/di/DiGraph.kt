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
}
