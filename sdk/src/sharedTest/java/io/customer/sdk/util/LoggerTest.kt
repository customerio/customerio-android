package io.customer.sdk.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
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
        val staticSettingsProvider: StaticSettingsProvider = mock()
        whenever(staticSettingsProvider.isDebuggable).thenReturn(true)

        val logger = LogcatLogger(staticSettingsProvider)

        logger.logLevel shouldBeEqualTo CioLogLevel.DEBUG
    }

    @Test
    fun verifySDKNotInitialized_givenReleaseEnvironment_expectLogLevelErrors() {
        val staticSettingsProvider: StaticSettingsProvider = mock()
        whenever(staticSettingsProvider.isDebuggable).thenReturn(false)

        val logger = LogcatLogger(staticSettingsProvider)

        logger.logLevel shouldBeEqualTo CioLogLevel.ERROR
    }

    @Test
    fun verifySDKInitialized_givenDebugEnvironment_expectLogLevelAsDefined() {
        val staticSettingsProvider: StaticSettingsProvider = mock()
        whenever(staticSettingsProvider.isDebuggable).thenReturn(true)
        val givenLogLevel = CioLogLevel.INFO

        val logger = LogcatLogger(staticSettingsProvider)
        logger.setPreferredLogLevel(givenLogLevel)

        logger.logLevel shouldBeEqualTo givenLogLevel
    }

    @Test
    fun verifySDKInitialized_givenReleaseEnvironment_expectLogLevelAsDefined() {
        val staticSettingsProvider: StaticSettingsProvider = mock()
        whenever(staticSettingsProvider.isDebuggable).thenReturn(false)
        val givenLogLevel = CioLogLevel.NONE

        val logger = LogcatLogger(staticSettingsProvider)
        logger.setPreferredLogLevel(givenLogLevel)

        logger.logLevel shouldBeEqualTo givenLogLevel
    }
}
