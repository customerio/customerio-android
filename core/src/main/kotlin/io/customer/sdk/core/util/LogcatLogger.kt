package io.customer.sdk.core.util

import android.util.Log

/**
 * Convenience wrapper around Android's [Log] class to avoid unit testing static calls
 */
internal class LogcatLogger {
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(tag, msg)
        } else {
            Log.e(tag, msg, throwable)
        }
    }
}
