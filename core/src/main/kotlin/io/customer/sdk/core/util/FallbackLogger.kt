package io.customer.sdk.core.util

import io.customer.sdk.core.di.SDKComponent

/**
 * A fallback in case the SDK failed to initialize [Logger] instance before any logging calls happen.
 *
 * This allows us to expose the logger as non-null type without assuming any default logging levels.
 *
 * If any logs of this class are seen, that means there is a bug with SDK initialization logic for the [Logger]
 */
internal class FallbackLogger : LoggerImpl(SDKComponent.DEFAULT_LOG_LEVEL) {
    init {
        error(
            "Attempting to use logger before it is initialized!",
            "FallbackLogger",
            IllegalStateException("Logger not initialized!")
        )
    }
}
