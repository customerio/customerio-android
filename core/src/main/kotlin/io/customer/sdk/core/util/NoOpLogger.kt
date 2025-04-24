package io.customer.sdk.core.util

import android.util.Log
import java.lang.IllegalStateException

/**
 * A fallback in case the SDK failed to initialize [Logger] instance before any logging calls happen.
 *
 * This allows us to expose the logger as non-null type without assuming any default logging levels.
 *
 * If any logs of this class are seen, that means there is a bug with SDK initialization logic for the [Logger]
 */
internal object NoOpLogger : Logger {
    override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) {
        Log.e(LoggerImpl.TAG, "NoOpLogger: Attempting to use logger before it is initialized!")
    }

    override fun info(message: String, tag: String?) {
        Log.i(
            LoggerImpl.TAG,
            "NoOpLogger: Attempting to use logger before it is initialized! -> $message"
        )
    }

    override fun debug(message: String, tag: String?) {
        Log.d(
            LoggerImpl.TAG,
            "NoOpLogger: Attempting to use logger before it is initialized! -> $message"
        )
    }

    override fun error(message: String, tag: String?, throwable: Throwable?) {
        Log.e(
            LoggerImpl.TAG,
            "NoOpLogger: Attempting to use logger before it is initialized! -> $message",
            IllegalStateException("Logger not initialized!")
        )
    }
}
