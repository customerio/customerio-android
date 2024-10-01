package io.customer.sdk.core.util

import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.sdk.core.environment.BuildEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class LoggerTest : JUnit5Test() {
    @Test
    fun shouldLog_givenNone() {
        val configLogLevelSet = CioLogLevel.NONE

        assertShouldLog(
            configLogLevelSet,
            error = false,
            info = false,
            debug = false
        )
    }

    @Test
    fun shouldLog_givenError() {
        val configLogLevelSet = CioLogLevel.ERROR

        assertShouldLog(
            configLogLevelSet,
            error = true,
            info = false,
            debug = false
        )
    }

    @Test
    fun shouldLog_givenInfo() {
        val configLogLevelSet = CioLogLevel.INFO

        assertShouldLog(
            configLogLevelSet,
            error = true,
            info = true,
            debug = false
        )
    }

    @Test
    fun shouldLog_givenDebug() {
        val configLogLevelSet = CioLogLevel.DEBUG

        assertShouldLog(
            configLogLevelSet,
            error = true,
            info = true,
            debug = true
        )
    }

    private fun assertShouldLog(
        levelSetBySdkConfig: CioLogLevel,
        error: Boolean,
        info: Boolean,
        debug: Boolean
    ) {
        levelSetBySdkConfig.shouldLog(CioLogLevel.ERROR) shouldBeEqualTo error
        levelSetBySdkConfig.shouldLog(CioLogLevel.INFO) shouldBeEqualTo info
        levelSetBySdkConfig.shouldLog(CioLogLevel.DEBUG) shouldBeEqualTo debug
    }

    @Test
    fun verifySDKNotInitialized_givenDebugEnvironment_expectLogLevelDebug() {
        val buildEnvironment: BuildEnvironment = mockk(relaxed = true)
        every { buildEnvironment.debugModeEnabled } returns true

        val logger = LogcatLogger(buildEnvironment)

        logger.logLevel shouldBeEqualTo CioLogLevel.DEBUG
    }

    @Test
    fun verifySDKNotInitialized_givenReleaseEnvironment_expectLogLevelErrors() {
        val buildEnvironment: BuildEnvironment = mockk(relaxed = true)
        every { buildEnvironment.debugModeEnabled } returns false

        val logger = LogcatLogger(buildEnvironment)

        logger.logLevel shouldBeEqualTo CioLogLevel.ERROR
    }

    @Test
    fun verifySDKInitialized_givenDebugEnvironment_expectLogLevelAsDefined() {
        val buildEnvironment: BuildEnvironment = mockk(relaxed = true)
        every { buildEnvironment.debugModeEnabled } returns true
        val givenLogLevel = CioLogLevel.INFO

        val logger = LogcatLogger(buildEnvironment)
        logger.logLevel = givenLogLevel

        logger.logLevel shouldBeEqualTo givenLogLevel
    }

    @Test
    fun verifySDKInitialized_givenReleaseEnvironment_expectLogLevelAsDefined() {
        val buildEnvironment: BuildEnvironment = mockk(relaxed = true)
        every { buildEnvironment.debugModeEnabled } returns false
        val givenLogLevel = CioLogLevel.NONE

        val logger = LogcatLogger(buildEnvironment)
        logger.logLevel = givenLogLevel

        logger.logLevel shouldBeEqualTo givenLogLevel
    }

    @Test
    fun logIfMatchesCriteria_givenLogLevelNone_shouldNotInvokeAnyLogs() {
        val logger = spyk(LogcatLogger(mockk(relaxed = true)))
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
        logger.logLevel = CioLogLevel.NONE
        logger.setLogDispatcher(logEventListenerMock)

        val givenErrorMessage = "Test error message"
        logger.info(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        val givenDebugMessage = "Test debug message"
        logger.info(givenDebugMessage)

        assertNoInteractions(logEventListenerMock)
    }

    @Test
    fun logIfMatchesCriteria_givenLogLevelError_shouldInvokeErrorLogOnly() {
        val logger = spyk(LogcatLogger(mockk(relaxed = true)))
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
        logger.logLevel = CioLogLevel.ERROR
        logger.setLogDispatcher(logEventListenerMock)

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        val givenDebugMessage = "Test debug message"
        logger.debug(givenDebugMessage)

        assertCalledOnce { logEventListenerMock(CioLogLevel.ERROR, givenErrorMessage) }
        assertCalledNever { logEventListenerMock(CioLogLevel.INFO, givenInfoMessage) }
        assertCalledNever { logEventListenerMock(CioLogLevel.DEBUG, givenDebugMessage) }
    }

    @Test
    fun logIfMatchesCriteria_givenLogLevelInfo_shouldInvokeInfoAndErrorLogs() {
        val logger = spyk(LogcatLogger(mockk(relaxed = true)))
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
        logger.logLevel = CioLogLevel.INFO
        logger.setLogDispatcher(logEventListenerMock)

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        val givenDebugMessage = "Test debug message"
        logger.debug(givenDebugMessage)

        assertCalledOnce { logEventListenerMock(CioLogLevel.ERROR, givenErrorMessage) }
        assertCalledOnce { logEventListenerMock(CioLogLevel.INFO, givenInfoMessage) }
        assertCalledNever { logEventListenerMock(CioLogLevel.DEBUG, givenDebugMessage) }
    }

    @Test
    fun logIfMatchesCriteria_givenLogLevelDebug_shouldInvokeAllLogs() {
        val logger = spyk(LogcatLogger(mockk(relaxed = true)))
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
        logger.logLevel = CioLogLevel.DEBUG
        logger.setLogDispatcher(logEventListenerMock)

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        val givenDebugMessage = "Test debug message"
        logger.debug(givenDebugMessage)

        assertCalledOnce { logEventListenerMock(CioLogLevel.ERROR, givenErrorMessage) }
        assertCalledOnce { logEventListenerMock(CioLogLevel.INFO, givenInfoMessage) }
        assertCalledOnce { logEventListenerMock(CioLogLevel.DEBUG, givenDebugMessage) }
    }
}
