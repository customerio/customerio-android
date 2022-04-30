package io.customer.base.comunication

import androidx.annotation.WorkerThread
import io.customer.base.data.Result

/**
 *
 * [Action] can be used by following ways:
 * [execute]: Action may be executed synchronously or asynchronously with [enqueue]
 *
 */
interface Action<T : Any> {
    /**
     * Synchronously send the request and return its response. Only call this from a background thread.
     */
    @WorkerThread
    public fun execute(): Result<T>

    /**
     * Asynchronously send the request and notify callback of its response.
     */
    fun enqueue(callback: Callback<T>)

    /**
     * Executes the call asynchronously, on a background thread. Safe to call from the main
     * thread.
     *
     * To get notified of the result and handle errors, use enqueue(callback) instead.
     */
    fun enqueue(): Unit = enqueue {}

    /**
     * Cancels the execution of the call, if cancellation is supported for the operation.
     *
     * Note that calls can not be cancelled when running them with [execute].
     */
    fun cancel()

    fun interface Callback<T : Any> {
        fun onResult(result: Result<T>)
    }
}
