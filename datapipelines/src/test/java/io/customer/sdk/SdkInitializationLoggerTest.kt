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

class SdkInitializationLoggerTest : JUnitTest() {

    private val mockLogger = mockk<Logger>()
    private val initLogger = SdkInitializationLogger(mockLogger)

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
