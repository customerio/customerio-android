package io.customer.sdk.core.util

import android.util.Log
import io.customer.sdk.core.environment.BuildEnvironment

interface Logger {
    var logLevel: CioLogLevel
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
        fun getLogLevel(level: String?, fallback: CioLogLevel = NONE): CioLogLevel {
            return values().find { value -> value.name.equals(level, ignoreCase = true) }
                ?: fallback
        }
    }
}

internal fun CioLogLevel.shouldLog(levelForMessage: CioLogLevel): Boolean {
    return when (this) {
        CioLogLevel.NONE -> false
        CioLogLevel.ERROR -> levelForMessage == CioLogLevel.ERROR
        CioLogLevel.INFO -> levelForMessage == CioLogLevel.ERROR || levelForMessage == CioLogLevel.INFO
        CioLogLevel.DEBUG -> true
    }
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

    override fun info(message: String) {
        runIfMeetsLogLevelCriteria(CioLogLevel.INFO) {
            Log.i(TAG, message)
        }
    }

    override fun debug(message: String) {
        runIfMeetsLogLevelCriteria(CioLogLevel.DEBUG) {
            Log.d(TAG, message)
        }
    }

    override fun error(message: String) {
        runIfMeetsLogLevelCriteria(CioLogLevel.ERROR) {
            Log.e(TAG, message)
        }
    }

    private fun runIfMeetsLogLevelCriteria(levelForMessage: CioLogLevel, block: () -> Unit) {
        val shouldLog = logLevel.shouldLog(levelForMessage)

        if (shouldLog) block()
    }

    companion object {
        const val TAG = "[CIO]"
    }
}
