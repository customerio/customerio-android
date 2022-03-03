package io.customer.sdk.util

/**
 * Creating a Kotlin singleton is easy by just using `object`. However, in this project, we dont want
 * global singleton instances, we want a singleton instance per site-id. This class makes that easy.
 *
 * How to do:
 * ```
 * class AndroidSingleScheduleTimer private constructor(
 *   private val timer: SimpleTimer
 ): SingleScheduleTimer {
 class Factory(val timer: SimpleTimer): SandboxedSingleton<AndroidSingleScheduleTimer>() {
 override fun getNewInstance(siteId: String): AndroidSingleScheduleTimer {
 return AndroidSingleScheduleTimer(timer)
 }
 }
 ...
 * ```
 *
 * Then you would use it: `AndroidSingleScheduleTimer.Factory(timer).getInstance(siteId)`
 */
abstract class SandboxedSingleton<Singleton> {

    abstract fun getNewInstance(siteId: String): Singleton

    companion object {
        val instances: MutableMap<String, Any> = mutableMapOf()
    }

    @Suppress("UNCHECKED_CAST")
    fun getInstance(siteId: String): Singleton {
        synchronized(this) {
            instances[siteId]?.let { existingSingleton ->
                return existingSingleton as Singleton
            }

            val newInstance = getNewInstance(siteId)
            instances[siteId] = newInstance as Any

            return newInstance
        }
    }
}
