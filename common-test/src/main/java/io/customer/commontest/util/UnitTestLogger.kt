package io.customer.commontest.util

import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger

/**
 * Logger implementation for unit tests.
 * This logger implementation logs messages messages to the console based on
 * the log level set to help debug issues while running unit tests.
 */
class UnitTestLogger : Logger {
    override var logLevel: CioLogLevel = CioLogLevel.DEBUG
    override var logDispatcher: ((CioLogLevel, String) -> Unit) = { level, message ->
        println("[$level]: $message")
    }

    override fun info(message: String) {
        log(CioLogLevel.INFO, message)
    }

    override fun debug(message: String) {
        log(CioLogLevel.DEBUG, message)
    }

    override fun error(message: String) {
        log(CioLogLevel.ERROR, message)
    }

    private fun log(levelForMessage: CioLogLevel, message: String) {
        val shouldLog = logLevel >= levelForMessage

        if (shouldLog) {
            logDispatcher.invoke(levelForMessage, message)
        }
    }
}
