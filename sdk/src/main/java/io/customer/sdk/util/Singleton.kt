package io.customer.sdk.util

/**
 * Quick way to make a class a singleton.
 *
 * ```
 * class Foo(
 *   param1: Param1,
 *   param2: Param2
 * ) {
 *   companion object SingletonHolder: Singleton<QueueRunRequest>()
 * }
 *
 * // to use:
 * Foo.SingletonHolder.getInstanceOrCreate {
 *   Foo(param1, param2)
 * }
 * ```
 */
abstract class Singleton<INST> {
    @Volatile var instance: INST? = null

    fun getInstanceOrCreate(newInstanceCreator: () -> INST): INST {
        return instance ?: newInstanceCreator().also {
            instance = it
        }
    }
}
