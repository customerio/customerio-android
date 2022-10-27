package io.customer.sdk.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class BaseLogcatLoggerTest : BaseTest() {

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
}

@RunWith(AndroidJUnit4::class)
class PreInitializationLogcatLoggerTest : BaseTest() {

    @Test
    fun logLevel_givenSdkNotInitialized_givenDebugEnvironment_expectLogLevelDebug() {
        val givenStaticSettingsProvider = getStaticSettingsProvider(isDebugEnvironment = true)

        val logger = PreInitializationLogcatLogger(givenStaticSettingsProvider)

        logger.logLevel shouldBeEqualTo CioLogLevel.DEBUG
    }

    @Test
    fun logLevel_givenSdkNotInitialized_givenReleaseEnvironment_expectLogLevelError() {
        val givenStaticSettingsProvider = getStaticSettingsProvider(isDebugEnvironment = false)

        val logger = PreInitializationLogcatLogger(givenStaticSettingsProvider)

        logger.logLevel shouldBeEqualTo CioLogLevel.ERROR
    }

    @Test
    fun logLevel_givenSdkInitialized_givenDebugEnvironment_expectLogLevelGivenFromConfig() {
        val givenStaticSettingsProvider = getStaticSettingsProvider(isDebugEnvironment = true)
        val givenLogLevel = CioLogLevel.INFO

        val logger = PreInitializationLogcatLogger(givenStaticSettingsProvider)
        logger.setPreferredLogLevel(givenLogLevel)

        logger.logLevel shouldBeEqualTo givenLogLevel
    }

    @Test
    fun logLevel_givenSdkInitialized_givenReleaseEnvironment_expectLogLevelGivenFromConfig() {
        val givenStaticSettingsProvider = getStaticSettingsProvider(isDebugEnvironment = false)
        val givenLogLevel = CioLogLevel.NONE

        val logger = PreInitializationLogcatLogger(givenStaticSettingsProvider)
        logger.setPreferredLogLevel(givenLogLevel)

        logger.logLevel shouldBeEqualTo givenLogLevel
    }

    @Test
    fun shouldLogMessagesToFile_givenDebugEnvironment_expectFalse() {
        val givenStaticSettingsProvider = getStaticSettingsProvider(isDebugEnvironment = true)

        val logger = PreInitializationLogcatLogger(givenStaticSettingsProvider)

        logger.shouldLogMessagesToFile() shouldBeEqualTo false
    }

    @Test
    fun shouldLogMessagesToFile_givenReleaseEnvironment_expectFalse() {
        val givenStaticSettingsProvider = getStaticSettingsProvider(isDebugEnvironment = false)

        val logger = PreInitializationLogcatLogger(givenStaticSettingsProvider)

        logger.shouldLogMessagesToFile() shouldBeEqualTo false
    }

    private fun getStaticSettingsProvider(isDebugEnvironment: Boolean): StaticSettingsProvider {
        return mock<StaticSettingsProvider>().apply {
            whenever(isDebuggable).thenReturn(isDebugEnvironment)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class PostInitializationLogcatLoggerTest : BaseTest() {

    @Test
    fun logLevel_givenLogLevelInSdkConfig_expectLogLevelSetFromSdkConfig() {
        var givenLogLevel = CioLogLevel.INFO

        var logger = PostInitializationLogcatLogger(createConfig(logLevel = givenLogLevel))

        logger.logLevel shouldBeEqualTo givenLogLevel

        // change log level to make sure the log level in Logger changes based on SDK config and isn't responding to a default value set in the Logger
        givenLogLevel = CioLogLevel.DEBUG

        logger = PostInitializationLogcatLogger(createConfig(logLevel = givenLogLevel))

        logger.logLevel shouldBeEqualTo givenLogLevel
    }

    @Test
    fun shouldLogMessageToFile_givenInDeveloperMode_expectTrue() {
        val givenInDeveloperMode = true

        val logger = PostInitializationLogcatLogger(createConfig(developerMode = givenInDeveloperMode))

        logger.shouldLogMessagesToFile() shouldBeEqualTo true
    }

    @Test
    fun shouldLogMessageToFile_givenNotInDeveloperMode_expectFalse() {
        val givenInDeveloperMode = false

        val logger = PostInitializationLogcatLogger(createConfig(developerMode = givenInDeveloperMode))

        logger.shouldLogMessagesToFile() shouldBeEqualTo false
    }
}
