package io.customer.sdk

import io.customer.commontest.extensions.assertCalledOnce
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.amshove.kluent.internal.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DataPipelinesLoggerTest : JUnitTest() {

    private val mockLogger = mockk<Logger>()
    private val initLogger = DataPipelinesLogger(mockLogger)

    @BeforeEach
    fun setUp() {
        every { mockLogger.debug(any(), any()) } just runs
        every { mockLogger.info(any(), any()) } just runs
        every { mockLogger.error(any(), any(), any()) } just runs
    }

    @Test
    fun test_coreSdkInitStart_forwardsCorrectLog() {
        val version = Version.version
        initLogger.coreSdkInitStart()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Init",
                message = "Creating new instance of CustomerIO SDK version: $version..."
            )
        }
    }

    @Test
    fun test_coreSdkAlreadyInitialized_forwardsCorrectLog() {
        initLogger.coreSdkAlreadyInitialized()

        val capturedThrowable = slot<Throwable>()
        assertCalledOnce {
            mockLogger.error(
                tag = eq("Init"),
                message = eq("CustomerIO instance is already initialized, skipping the initialization"),
                throwable = capture(capturedThrowable)
            )
        }
        assert(capturedThrowable.captured is IllegalStateException)
        assertEquals("CustomerIO SDK already initialized", capturedThrowable.captured.message)
    }

    @Test
    fun test_coreSdkInitSuccess_forwardsCorrectLog() {
        initLogger.coreSdkInitSuccess()

        assertCalledOnce {
            mockLogger.info(
                tag = "Init",
                message = "CustomerIO SDK is initialized and ready to use"
            )
        }
    }

    @Test
    fun test_moduleInitStart_forwardsCorrectLog() {
        initLogger.moduleInitStart(TestModule("TestModuleName", "TestModuleConfigReadableString"))

        assertCalledOnce {
            mockLogger.debug(
                tag = "Init",
                message = "Initializing SDK module TestModuleName with config: TestModuleConfigReadableString..."
            )
        }
    }

    @Test
    fun test_moduleInitSuccess_forwardsCorrectLog() {
        initLogger.moduleInitSuccess(TestModule("AnotherModule", "Whatever"))

        assertCalledOnce {
            mockLogger.info(
                tag = "Init",
                message = "CustomerIO AnotherModule module is initialized and ready to use"
            )
        }
    }

    @Test
    fun test_logStoringDevicePushToken_forwardsCorrectLog() {
        val token = "fcm-token"
        val userId = "user-id"
        initLogger.logStoringDevicePushToken(token, userId)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Storing device token: $token for user profile: $userId"
            )
        }
    }

    @Test
    fun test_logStoringBlankPushToken_forwardsCorrectLog() {
        initLogger.logStoringBlankPushToken()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Attempting to register blank token, ignoring request"
            )
        }
    }

    @Test
    fun test_logRegisteringPushToken_forwardsCorrectLog() {
        val token = "fcm-token"
        val userId = "user-id"
        initLogger.logRegisteringPushToken(token, userId)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Registering device token: $token for user profile: $userId"
            )
        }
    }

    @Test
    fun test_logPushTokenRefreshed_forwardsCorrectLog() {
        initLogger.logPushTokenRefreshed()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Token refreshed, deleting old token to avoid registering same device multiple times"
            )
        }
    }

    @Test
    fun test_automaticTokenRegistrationForNewProfiled_forwardsCorrectLog() {
        initLogger.automaticTokenRegistrationForNewProfile("token", "user-id")

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Automatically registering device token: token to newly identified profile: user-id"
            )
        }
    }

    @Test
    fun test_logDeletingTokenDueToNewProfileIdentification_forwardsCorrectLog() {
        initLogger.logDeletingTokenDueToNewProfileIdentification()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Deleting device token before identifying new profile"
            )
        }
    }

    @Test
    fun test_logTrackingDevicesAttributesWithoutValidToken_forwardsCorrectLog() {
        initLogger.logTrackingDevicesAttributesWithoutValidToken()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "No device token found. ignoring request to track device attributes"
            )
        }
    }

    private class TestModule(
        private val name: String,
        private val configString: String
    ) : CustomerIOModule<CustomerIOModuleConfig> {
        override val moduleName: String
            get() = name
        override val moduleConfig: CustomerIOModuleConfig
            get() = object : CustomerIOModuleConfig {
                override fun toString(): String {
                    return configString
                }
            }

        override fun initialize() {
            // Do nothing
        }
    }
}
