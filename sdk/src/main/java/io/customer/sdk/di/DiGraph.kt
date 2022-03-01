package io.customer.sdk.di

interface DiGraph {
    var overrides: MutableMap<String, Any>
}

// Call when you want to override a dependency with a mock in test functions.
fun <DEP> DiGraph.overrideDependency(dependency: Class<DEP>, value: DEP) {
    overrides[dependency.simpleName] = value as Any
}
inline fun <reified DEP> DiGraph.override(): DEP? = overrides[DEP::class.java.simpleName] as? DEP

abstract class DiGraphCompanion<DiGraphSubclass : DiGraph> {
    @Volatile private var instances: MutableMap<String, DiGraphSubclass> = mutableMapOf()

    abstract fun newInstance(siteId: String): DiGraphSubclass

    /**
     * Each site id has it's own singleton instance of the DI graph. Each site id should be sandboxed from
     * each other with it's resources.
     *
     * The DI graph instances need to be persisted in memory because if a customer uses the non-singleton API
     * of the SDK by constructing new instances of [CustomerIO], those instances can be GCed but we don't want the
     * graph to be for running tasks such as background queue to continue running.
     */
    fun getInstance(siteId: String): DiGraphSubclass {
        synchronized(this) {
            val existingInstance = instances[siteId]
            if (existingInstance != null) return existingInstance

            val newInstance = newInstance(siteId)
            instances[siteId] = newInstance

            return newInstance
        }
    }
}
