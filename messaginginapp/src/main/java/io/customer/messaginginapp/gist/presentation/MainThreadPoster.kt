package io.customer.messaginginapp.gist.presentation

import android.os.Handler
import android.os.Looper

/**
 * Allows for testability by mocking the Handler.
 */
internal interface MainThreadPoster {
    fun post(block: () -> Unit)
}

/**
 * Default implementation using Android Handler.
 */
internal class HandlerMainThreadPoster : MainThreadPoster {
    private val handler = Handler(Looper.getMainLooper())

    override fun post(block: () -> Unit) {
        handler.post(block)
    }
}
