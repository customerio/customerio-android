package io.customer.sdk.core.util

import android.util.Log
import io.customer.sdk.core.environment.BuildEnvironment

interface Logger {
    // Log level to determine which logs to print
    // This is the log level set by the user in configurations or the default log level
    var logLevel: CioLogLevel

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

    fun info(message: String)
    fun debug(message: String)
    fun error(message: String)
}

enum class CioLogLevel {
    NONE,
    ERROR,
    INFO,
    DEBUG;

    companion object {
        val DEFAULT = ERROR

        fun getLogLevel(level: String?, fallback: CioLogLevel = DEFAULT): CioLogLevel {
            return values().find { value -> value.name.equals(level, ignoreCase = true) }
                ?: fallback
        }
    }
}

internal fun CioLogLevel.shouldLog(levelForMessage: CioLogLevel): Boolean {
    return this >= levelForMessage
}

class LogcatLogger(
    private val buildEnvironment: BuildEnvironment
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

    override fun info(message: String) {
        logIfMatchesCriteria(CioLogLevel.INFO, message)
    }

    override fun debug(message: String) {
        logIfMatchesCriteria(CioLogLevel.DEBUG, message)
    }

    override fun error(message: String) {
        logIfMatchesCriteria(CioLogLevel.ERROR, message)
    }

    private fun logIfMatchesCriteria(levelForMessage: CioLogLevel, message: String) {
        val shouldLog = logLevel.shouldLog(levelForMessage)

        if (shouldLog) {
            // Dispatch log event to log dispatcher only if the log level is met and the dispatcher is set
            // Otherwise, log to Logcat
            logDispatcher?.invoke(levelForMessage, message) ?: when (levelForMessage) {
                CioLogLevel.NONE -> {}
                CioLogLevel.ERROR -> Log.e(TAG, message)
                CioLogLevel.INFO -> Log.i(TAG, message)
                CioLogLevel.DEBUG -> Log.d(TAG, message)
            }
        }
    }

    companion object {
        const val TAG = "[CIO]"
    }
}
