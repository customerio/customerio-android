package io.customer.messagingpush.logger

import io.customer.commontest.extensions.assertCalledOnce
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PushNotificationLoggerTest : JUnitTest() {

    private val mockLogger = mockk<Logger>()
    private val pushLogger = PushNotificationLogger(mockLogger)

    @BeforeEach
    fun setUp() {
        every { mockLogger.debug(any(), any()) } just runs
        every { mockLogger.error(any(), any(), any()) } just runs
    }

    @Test
    fun test_logGooglePlayServicesAvailable_forwardsCorrectCallToLogger() {
        pushLogger.logGooglePlayServicesAvailable()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Google Play Services is available for this device"
            )
        }
    }

    @Test
    fun test_logGooglePlayServicesUnavailable_forwardsCorrectCallToLogger() {
        pushLogger.logGooglePlayServicesUnavailable(499)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Google Play Services is NOT available for this device with result: 499"
            )
        }
    }

    @Test
    fun test_logGooglePlayServicesAvailabilityCheckFailed_forwardsCorrectCallToLogger() {
        val message = "Play service not available"
        val throwable = IllegalStateException(message)
        pushLogger.logGooglePlayServicesAvailabilityCheckFailed(throwable)

        assertCalledOnce {
            mockLogger.error(
                tag = "Push",
                message = "Checking Google Play Service availability check failed with error: : $message",
                throwable = throwable
            )
        }
    }
}
