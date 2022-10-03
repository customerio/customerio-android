package io.customer.sdk.util

import android.util.Log
import androidx.annotation.VisibleForTesting

interface Logger {
    fun info(message: String)
    fun debug(message: String)
    fun error(message: String)
}

enum class CioLogLevel {
    NONE,
    ERROR,
    INFO,
    DEBUG;

    fun shouldLog(levelForMessage: CioLogLevel): Boolean {
        return when (this) {
            NONE -> false
            ERROR -> levelForMessage == ERROR
            INFO -> levelForMessage == ERROR || levelForMessage == INFO
            DEBUG -> true
        }
    }
}

internal class LogcatLogger(
    private val staticSettingsProvider: StaticSettingsProvider
) : Logger {
    // Log level defined by user in configurations
    private var preferredLogLevel: CioLogLevel? = null

    // Fallback log level to be used only if log level is not yet defined by the user
    private val fallbackLogLevel
        get() = if (staticSettingsProvider.isDebuggable) CioLogLevel.DEBUG
        else CioLogLevel.ERROR

    // Prefer user log level; fallback to default only till the user defined value is not received
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    val logLevel: CioLogLevel
        get() = preferredLogLevel ?: fallbackLogLevel

    fun setPreferredLogLevel(logLevel: CioLogLevel) {
        preferredLogLevel = logLevel
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
