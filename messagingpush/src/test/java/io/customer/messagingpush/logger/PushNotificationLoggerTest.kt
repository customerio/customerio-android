package io.customer.messagingpush.logger

import com.google.firebase.messaging.RemoteMessage
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

    @Test
    fun test_obtainingTokenStarted_forwardsCorrectCallToLogger() {
        pushLogger.obtainingTokenStarted()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Getting current device token from Firebase messaging on app launch"
            )
        }
    }

    @Test
    fun test_obtainingTokenSuccess_forwardsCorrectCallToLogger() {
        val token = "fcm-token"
        pushLogger.obtainingTokenSuccess(token)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Got current device token: $token"
            )
        }
    }

    @Test
    fun test_obtainingTokenFailed_forwardsCorrectCallToLogger() {
        val errorMessage = "Error message!"
        val throwable = IllegalStateException(errorMessage)
        pushLogger.obtainingTokenFailed(throwable)

        assertCalledOnce {
            mockLogger.error(
                tag = "Push",
                message = "Failed to get device token with error: $errorMessage",
                throwable = throwable
            )
        }
    }

    @Test
    fun test_logShowingPushNotification_forwardsCorrectCallToLogger() {
        val notification = mockk<RemoteMessage.Notification>()
        every { notification.title } returns "testTitle"
        every { notification.body } returns "test body"
        every { notification.icon } returns "testIcon"
        every { notification.color } returns "testColor"
        every { notification.imageUrl } returns null
        val data = mapOf("testKey" to "testValue")
        val message = mockk<RemoteMessage>()
        every { message.notification } returns notification
        every { message.data } returns data

        pushLogger.logShowingPushNotification(message)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Showing notification for message: Notification:\n" +
                    "  title = testTitle\n" +
                    "  body = test body\n" +
                    "  icon = testIcon\n" +
                    "  color = testColor\n" +
                    "  imageUrl = null\n" +
                    "Data: {testKey=testValue}\n"
            )
        }
    }
}
