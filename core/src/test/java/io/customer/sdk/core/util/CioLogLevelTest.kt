package io.customer.sdk.core.util

import io.customer.commontest.core.JUnit5Test
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class CioLogLevelTest : JUnit5Test() {
    @Test
    fun getLogLevel_givenNamesWithMatchingCase_expectCorrectLogLevel() {
        val logLevelNone = CioLogLevel.getLogLevel("NONE")
        val logLevelError = CioLogLevel.getLogLevel("ERROR")
        val logLevelInfo = CioLogLevel.getLogLevel("INFO")
        val logLevelDebug = CioLogLevel.getLogLevel("DEBUG")

        logLevelNone shouldBeEqualTo CioLogLevel.NONE
        logLevelError shouldBeEqualTo CioLogLevel.ERROR
        logLevelInfo shouldBeEqualTo CioLogLevel.INFO
        logLevelDebug shouldBeEqualTo CioLogLevel.DEBUG
    }

    @Test
    fun getLogLevel_givenNamesWithDifferentCase_expectCorrectLogLevel() {
        val logLevelNone = CioLogLevel.getLogLevel("None")
        val logLevelError = CioLogLevel.getLogLevel("eRROR")
        val logLevelInfo = CioLogLevel.getLogLevel("info")
        val logLevelDebug = CioLogLevel.getLogLevel("DeBuG")

        logLevelNone shouldBeEqualTo CioLogLevel.NONE
        logLevelError shouldBeEqualTo CioLogLevel.ERROR
        logLevelInfo shouldBeEqualTo CioLogLevel.INFO
        logLevelDebug shouldBeEqualTo CioLogLevel.DEBUG
    }

    @Test
    fun getLogLevel_givenInvalidValue_expectFallbackLogLevel() {
        val parsedLogLevel = CioLogLevel.getLogLevel("invalid")

        parsedLogLevel shouldBeEqualTo CioLogLevel.ERROR
    }

    @Test
    fun getLogLevel_givenEmptyValue_expectFallbackLogLevel() {
        val parsedLogLevel = CioLogLevel.getLogLevel("")

        parsedLogLevel shouldBeEqualTo CioLogLevel.ERROR
    }

    @Test
    fun getLogLevel_givenNull_expectFallbackLogLevel() {
        val parsedLogLevel = CioLogLevel.getLogLevel(null)

        parsedLogLevel shouldBeEqualTo CioLogLevel.ERROR
    }
}
