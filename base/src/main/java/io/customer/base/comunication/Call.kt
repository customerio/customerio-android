package io.customer.base.comunication

import androidx.annotation.WorkerThread

/**
 *
 * [Call] can be used either by following ways:
 * [execute]: Calls may be executed synchronously or asynchronously with [enqueue]
 *
 */
public interface Call<T : Any> {
    /**
     * Synchronously send the request and return its response. Only call this from a background thread.
     */
    @WorkerThread
    public fun execute(): Result<T>

    /**
     * Asynchronously send the request and notify callback of its response.
     */
    public fun enqueue(callback: Callback<T>)

    /**
     * Executes the call asynchronously, on a background thread. Safe to call from the main
     * thread.
     *
     * To get notified of the result and handle errors, use enqueue(callback) instead.
     */
    public fun enqueue(): Unit = enqueue {}

    /**
     * Cancels the execution of the call, if cancellation is supported for the operation.
     *
     * Note that calls can not be cancelled when running them with [execute].
     */
    public fun cancel()

    public fun interface Callback<T : Any> {
        public fun onResult(result: Result<T>)
    }
}
