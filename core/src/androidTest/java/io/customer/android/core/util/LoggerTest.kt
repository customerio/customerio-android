package io.customer.android.core.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.core.environment.BuildEnvironment
import io.customer.core.util.CioLogLevel
import io.customer.core.util.LogcatLogger
import io.customer.core.util.shouldLog
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class LoggerTest : BaseTest() {

    // Test log levels

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
        val buildEnvironment: BuildEnvironment = mock()
        whenever(buildEnvironment.debugModeEnabled).thenReturn(true)

        val logger = LogcatLogger(buildEnvironment)

        logger.logLevel shouldBeEqualTo CioLogLevel.DEBUG
    }

    @Test
    fun verifySDKNotInitialized_givenReleaseEnvironment_expectLogLevelErrors() {
        val buildEnvironment: BuildEnvironment = mock()
        whenever(buildEnvironment.debugModeEnabled).thenReturn(false)

        val logger = LogcatLogger(buildEnvironment)

        logger.logLevel shouldBeEqualTo CioLogLevel.ERROR
    }

    @Test
    fun verifySDKInitialized_givenDebugEnvironment_expectLogLevelAsDefined() {
        val buildEnvironment: BuildEnvironment = mock()
        whenever(buildEnvironment.debugModeEnabled).thenReturn(true)
        val givenLogLevel = CioLogLevel.INFO

        val logger = LogcatLogger(buildEnvironment)
        logger.logLevel = givenLogLevel

        logger.logLevel shouldBeEqualTo givenLogLevel
    }

    @Test
    fun verifySDKInitialized_givenReleaseEnvironment_expectLogLevelAsDefined() {
        val buildEnvironment: BuildEnvironment = mock()
        whenever(buildEnvironment.debugModeEnabled).thenReturn(false)
        val givenLogLevel = CioLogLevel.NONE

        val logger = LogcatLogger(buildEnvironment)
        logger.logLevel = givenLogLevel

        logger.logLevel shouldBeEqualTo givenLogLevel
    }
}
