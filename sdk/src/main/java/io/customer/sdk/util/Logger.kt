package io.customer.sdk.util

import android.util.Log
import io.customer.sdk.BuildConfig

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
    private var preferredLogLevel: CioLogLevel? = null
) : Logger {
    private val fallbackLogLevel = if (BuildConfig.DEBUG) CioLogLevel.DEBUG else CioLogLevel.ERROR
    private val logLevel: CioLogLevel get() = preferredLogLevel ?: fallbackLogLevel

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
