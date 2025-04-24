package io.customer.sdk.core.util

interface Logger {
    /**
     * Sets the dispatcher to handle log events based on the log level
     * Default implementation is to print logs to Logcat
     * In wrapper SDKs, this will be overridden to emit logs to more user-friendly channels
     * like console, etc.
     * If the dispatcher holds any reference to application context, the caller should ensure
     * to clear references when the context is destroyed.
     *
     * @param dispatcher Dispatcher to handle log events based on the log level, pass null
     * to reset to default
     */
    fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?)

    fun info(message: String, tag: String? = null)
    fun debug(message: String, tag: String? = null)
    fun error(message: String, tag: String? = null, throwable: Throwable? = null)
}
