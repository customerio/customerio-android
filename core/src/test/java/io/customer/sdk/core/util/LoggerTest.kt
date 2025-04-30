package io.customer.sdk.core.util

import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.sdk.core.environment.BuildEnvironment
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoggerTest : JUnit5Test() {

    private val mockLogger = mockk<LogcatLogger>()
    private val logger = LoggerImpl(
        object : BuildEnvironment {
            override val debugModeEnabled: Boolean
                get() = true
        },
        mockLogger
    )

    @BeforeEach
    fun setUp() {
        every { mockLogger.debug(any(), any()) } just runs
        every { mockLogger.info(any(), any()) } just runs
        every { mockLogger.error(any(), any(), any()) } just runs
    }

    @Test
    fun givenLogLevelNone_shouldNotInvokeAnyLogs() {
        logger.logLevel = CioLogLevel.NONE
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
        logger.setLogDispatcher(logEventListenerMock)

        logger.error("Test error message")
        logger.info("Test info message")
        logger.debug("Test debug message")

        assertNoInteractions(logEventListenerMock)
        assertNoInteractions(mockLogger)
    }

    @Test
    fun givenLogLevelNoneWithTag_shouldNotInvokeAnyLogs() {
        logger.logLevel = CioLogLevel.NONE
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
        logger.setLogDispatcher(logEventListenerMock)

        logger.error("Test error message", "AnyTag")
        logger.info("Test info message", "AnyTag")
        logger.debug("Test debug message", "AnyTag")

        assertNoInteractions(logEventListenerMock)
        assertNoInteractions(mockLogger)
    }

    @Test
    fun givenLogLevelError_shouldInvokeErrorLogOnly() {
        logger.logLevel = CioLogLevel.ERROR

        val throwable = IllegalStateException()
        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage, throwable = throwable)
        logger.info("Test info message")
        logger.debug("Test debug message")

        assertCalledOnce { mockLogger.error(LoggerImpl.TAG, givenErrorMessage, throwable) }
        assertCalledNever { mockLogger.info(any(), any()) }
        assertCalledNever { mockLogger.debug(any(), any()) }
    }

    @Test
    fun givenLogLevelErrorWithDispatcher_shouldInvokeErrorLogOnly() {
        logger.logLevel = CioLogLevel.ERROR
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
        logger.setLogDispatcher(logEventListenerMock)

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        logger.info("Test info message")
        logger.debug("Test debug message")

        assertCalledOnce { logEventListenerMock(CioLogLevel.ERROR, givenErrorMessage) }
        assertCalledNever { logEventListenerMock(eq(CioLogLevel.INFO), any()) }
        assertCalledNever { logEventListenerMock(eq(CioLogLevel.DEBUG), any()) }
        confirmVerified(logEventListenerMock)
    }

    @Test
    fun givenLogLevelErrorWithTag_shouldInvokeErrorLogOnly() {
        logger.logLevel = CioLogLevel.ERROR
        val tag = "AnyTag"

        logger.error("Test error message", tag)
        logger.info("Test info message", tag)
        logger.debug("Test debug message", tag)

        assertCalledOnce { mockLogger.error(LoggerImpl.TAG, "[AnyTag] Test error message", null) }
        assertCalledNever { mockLogger.info(any(), any()) }
        assertCalledNever { mockLogger.debug(any(), any()) }
    }

    @Test
    fun givenLogLevelInfo_shouldInvokeInfoAndErrorLogs() {
        logger.logLevel = CioLogLevel.INFO

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        logger.debug("Test debug message")

        assertCalledOnce { mockLogger.error(LoggerImpl.TAG, givenErrorMessage, null) }
        assertCalledOnce { mockLogger.info(LoggerImpl.TAG, givenInfoMessage) }
        assertCalledNever { mockLogger.debug(any(), any()) }
    }

    @Test
    fun givenLogLevelInfoWithDispatcher_shouldInvokeInfoAndErrorLogs() {
        logger.logLevel = CioLogLevel.INFO
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
        logger.setLogDispatcher(logEventListenerMock)

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        logger.debug("Test debug message")

        assertCalledOnce { logEventListenerMock(CioLogLevel.ERROR, givenErrorMessage) }
        assertCalledOnce { logEventListenerMock(CioLogLevel.INFO, givenInfoMessage) }
        assertCalledNever { logEventListenerMock(eq(CioLogLevel.DEBUG), any()) }
        confirmVerified(logEventListenerMock)
    }

    @Test
    fun givenLogLevelInfoWithTag_shouldInvokeInfoAndErrorLogs() {
        logger.logLevel = CioLogLevel.INFO
        val tag = "AnotherTag"

        logger.error("Test error message", tag)
        logger.info("Test info message", tag)
        logger.debug("Test debug message", tag)

        assertCalledOnce { mockLogger.error(LoggerImpl.TAG, "[AnotherTag] Test error message", null) }
        assertCalledOnce { mockLogger.info(LoggerImpl.TAG, "[AnotherTag] Test info message") }
        assertCalledNever { mockLogger.debug(any(), any()) }
    }

    @Test
    fun givenLogLevelDebug_shouldInvokeAllLogs() {
        logger.logLevel = CioLogLevel.DEBUG

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        val givenDebugMessage = "Test debug message"
        logger.debug(givenDebugMessage)

        assertCalledOnce { mockLogger.error(LoggerImpl.TAG, givenErrorMessage, null) }
        assertCalledOnce { mockLogger.info(LoggerImpl.TAG, givenInfoMessage) }
        assertCalledOnce { mockLogger.debug(LoggerImpl.TAG, givenDebugMessage) }
    }

    @Test
    fun givenLogLevelDebugWithDispatcher_shouldInvokeAllLogs() {
        logger.logLevel = CioLogLevel.DEBUG
        val logEventListenerMock = mockk<(CioLogLevel, String) -> Unit>(relaxed = true)
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
        confirmVerified(logEventListenerMock)
    }

    @Test
    fun givenLogLevelDebugWithTag_shouldInvokeAllLogs() {
        logger.logLevel = CioLogLevel.DEBUG
        val tag = "Tag?"

        logger.error("Test error message", tag)
        logger.info("Test info message", tag)
        logger.debug("Test debug message", tag)

        assertCalledOnce { mockLogger.error(LoggerImpl.TAG, "[Tag?] Test error message", null) }
        assertCalledOnce { mockLogger.info(LoggerImpl.TAG, "[Tag?] Test info message") }
        assertCalledOnce { mockLogger.debug(LoggerImpl.TAG, "[Tag?] Test debug message") }
    }
}
