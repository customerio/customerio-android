package io.customer.sdk.core.util

import io.customer.sdk.core.environment.BuildEnvironment

internal open class LoggerImpl(
    private val buildEnvironment: BuildEnvironment,
    private val actualLogger: LogcatLogger = LogcatLogger()
) : Logger {

    // Log level defined by user in configurations
    private var preferredLogLevel: CioLogLevel? = null

    // Fallback log level to be used only if log level is not yet defined by the user
    private val fallbackLogLevel
        get() = when {
            buildEnvironment.debugModeEnabled -> CioLogLevel.DEBUG
            else -> CioLogLevel.DEFAULT
        }

    // Prefer user log level; fallback to default only till the user defined value is not received
    override var logLevel: CioLogLevel
        get() = preferredLogLevel ?: fallbackLogLevel
        set(value) {
            preferredLogLevel = value
        }

    private var logDispatcher: ((CioLogLevel, String) -> Unit)? = null

    override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) {
        logDispatcher = dispatcher
    }

    override fun info(message: String, tag: String?) {
        logIfMatchesCriteria(CioLogLevel.INFO, message, tag, null)
    }

    override fun debug(message: String, tag: String?) {
        logIfMatchesCriteria(CioLogLevel.DEBUG, message, tag, null)
    }

    override fun error(message: String, tag: String?, throwable: Throwable?) {
        logIfMatchesCriteria(CioLogLevel.ERROR, message, tag, throwable)
    }

    private fun logIfMatchesCriteria(levelForMessage: CioLogLevel, message: String, tag: String?, throwable: Throwable?) {
        if (!shouldLog(levelForMessage)) return

        // Dispatch log event to log dispatcher only if the log level is met and the dispatcher is set
        // Otherwise, log to Logcat
        logDispatcher?.invoke(levelForMessage, message) ?: when (levelForMessage) {
            CioLogLevel.NONE -> {}
            CioLogLevel.ERROR -> actualLogger.error(TAG, prependTagToMessage(tag, message), throwable)
            CioLogLevel.INFO -> actualLogger.info(TAG, prependTagToMessage(tag, message))
            CioLogLevel.DEBUG -> actualLogger.debug(TAG, prependTagToMessage(tag, message))
        }
    }

    private fun shouldLog(levelForMessage: CioLogLevel): Boolean {
        return logLevel.priority >= levelForMessage.priority
    }

    private fun prependTagToMessage(tag: String?, message: String): String {
        if (tag.isNullOrBlank()) return message

        return "[$tag] $message"
    }

    companion object {
        const val TAG = "[CIO]"
    }
}
