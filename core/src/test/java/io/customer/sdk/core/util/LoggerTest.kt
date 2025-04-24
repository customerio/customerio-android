package io.customer.sdk.core.util

import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoggerTest : JUnit5Test() {

    private val mockLogger = mockk<LogcatLogger>()

    @BeforeEach
    fun setUp() {
        every { mockLogger.d(any(), any()) } just runs
        every { mockLogger.i(any(), any()) } just runs
        every { mockLogger.e(any(), any(), any()) } just runs
    }

    @Test
    fun givenLogLevelNone_shouldNotInvokeAnyLogs() {
        val logger = LoggerImpl(CioLogLevel.NONE, mockLogger)
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
        val logger = LoggerImpl(CioLogLevel.NONE, mockLogger)
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
        val logger = LoggerImpl(CioLogLevel.ERROR, mockLogger)

        val throwable = IllegalStateException()
        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage, throwable = throwable)
        logger.info("Test info message")
        logger.debug("Test debug message")

        assertCalledOnce { mockLogger.e(LoggerImpl.TAG, givenErrorMessage, throwable) }
        assertCalledNever { mockLogger.i(any(), any()) }
        assertCalledNever { mockLogger.d(any(), any()) }
    }

    @Test
    fun givenLogLevelErrorWithDispatcher_shouldInvokeErrorLogOnly() {
        val logger = LoggerImpl(CioLogLevel.ERROR, mockLogger)
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
        val logger = LoggerImpl(CioLogLevel.ERROR, mockLogger)
        val tag = "AnyTag"

        logger.error("Test error message", tag)
        logger.info("Test info message", tag)
        logger.debug("Test debug message", tag)

        assertCalledOnce { mockLogger.e(LoggerImpl.TAG, "[AnyTag] Test error message", null) }
        assertCalledNever { mockLogger.i(any(), any()) }
        assertCalledNever { mockLogger.d(any(), any()) }
    }

    @Test
    fun givenLogLevelInfo_shouldInvokeInfoAndErrorLogs() {
        val logger = LoggerImpl(CioLogLevel.INFO, mockLogger)

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        logger.debug("Test debug message")

        assertCalledOnce { mockLogger.e(LoggerImpl.TAG, givenErrorMessage, null) }
        assertCalledOnce { mockLogger.i(LoggerImpl.TAG, givenInfoMessage) }
        assertCalledNever { mockLogger.d(any(), any()) }
    }

    @Test
    fun givenLogLevelInfoWithDispatcher_shouldInvokeInfoAndErrorLogs() {
        val logger = LoggerImpl(CioLogLevel.INFO, mockLogger)
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
        val logger = LoggerImpl(CioLogLevel.INFO, mockLogger)
        val tag = "AnotherTag"

        logger.error("Test error message", tag)
        logger.info("Test info message", tag)
        logger.debug("Test debug message", tag)

        assertCalledOnce { mockLogger.e(LoggerImpl.TAG, "[AnotherTag] Test error message", null) }
        assertCalledOnce { mockLogger.i(LoggerImpl.TAG, "[AnotherTag] Test info message") }
        assertCalledNever { mockLogger.d(any(), any()) }
    }

    @Test
    fun givenLogLevelDebug_shouldInvokeAllLogs() {
        val logger = LoggerImpl(CioLogLevel.DEBUG, mockLogger)

        val givenErrorMessage = "Test error message"
        logger.error(givenErrorMessage)
        val givenInfoMessage = "Test info message"
        logger.info(givenInfoMessage)
        val givenDebugMessage = "Test debug message"
        logger.debug(givenDebugMessage)

        assertCalledOnce { mockLogger.e(LoggerImpl.TAG, givenErrorMessage, null) }
        assertCalledOnce { mockLogger.i(LoggerImpl.TAG, givenInfoMessage) }
        assertCalledOnce { mockLogger.d(LoggerImpl.TAG, givenDebugMessage) }
    }

    @Test
    fun givenLogLevelDebugWithDispatcher_shouldInvokeAllLogs() {
        val logger = LoggerImpl(CioLogLevel.DEBUG, mockLogger)
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
        val logger = LoggerImpl(CioLogLevel.DEBUG, mockLogger)
        val tag = "Tag?"

        logger.error("Test error message", tag)
        logger.info("Test info message", tag)
        logger.debug("Test debug message", tag)

        assertCalledOnce { mockLogger.e(LoggerImpl.TAG, "[Tag?] Test error message", null) }
        assertCalledOnce { mockLogger.i(LoggerImpl.TAG, "[Tag?] Test info message") }
        assertCalledOnce { mockLogger.d(LoggerImpl.TAG, "[Tag?] Test debug message") }
    }
}
