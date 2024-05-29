package io.customer.datapipelines.support.stubs

import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger

/**
 * Logger implementation for unit tests.
 * This logger implementation logs messages messages to the console based on
 * the log level set to help debug issues while running unit tests.
 */
class UnitTestLogger : Logger {
    override var logLevel: CioLogLevel = CioLogLevel.ERROR

    override fun info(message: String) {
        runIfMeetsLogLevelCriteria(CioLogLevel.INFO) {
            println("[INFO]: $message")
        }
    }

    override fun debug(message: String) {
        runIfMeetsLogLevelCriteria(CioLogLevel.DEBUG) {
            println("[DEBUG]: $message")
        }
    }

    override fun error(message: String) {
        runIfMeetsLogLevelCriteria(CioLogLevel.ERROR) {
            println("[ERROR]: $message")
        }
    }

    private fun runIfMeetsLogLevelCriteria(levelForMessage: CioLogLevel, block: () -> Unit) {
        val shouldLog = logLevel >= levelForMessage

        if (shouldLog) block()
    }
}
