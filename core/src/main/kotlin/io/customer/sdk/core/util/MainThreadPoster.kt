package io.customer.sdk.core.util

import android.os.Handler
import android.os.Looper
import io.customer.base.internal.InternalCustomerIOApi

/**
 * Abstracts posting work to the main thread.
 * Enables testability by allowing tests to mock or replace the implementation.
 */
@InternalCustomerIOApi
interface MainThreadPoster {
    fun post(block: () -> Unit)
}

/**
 * Default implementation using Android [Handler] with the main [Looper].
 */
@InternalCustomerIOApi
class HandlerMainThreadPoster : MainThreadPoster {
    private val handler = Handler(Looper.getMainLooper())

    override fun post(block: () -> Unit) {
        handler.post(block)
    }
}
