package io.customer.commontest.util

import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger

/**
 * Logger implementation for unit tests.
 * This logger implementation logs messages messages to the console based on
 * the log level set to help debug issues while running unit tests.
 */
class UnitTestLogger : Logger {

    override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) {
    }

    override fun info(message: String, tag: String?) {
        log(CioLogLevel.INFO, message)
    }

    override fun debug(message: String, tag: String?) {
        log(CioLogLevel.DEBUG, message)
    }

    override fun error(message: String, tag: String?, throwable: Throwable?) {
        log(CioLogLevel.ERROR, message)
    }

    private fun log(levelForMessage: CioLogLevel, message: String) {
        val shouldLog = CioLogLevel.DEBUG >= levelForMessage

        if (shouldLog) {
            println("[$levelForMessage]: $message")
        }
    }
}
