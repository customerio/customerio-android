package io.customer.messagingpush.logger

import android.os.Bundle
import com.google.firebase.messaging.RemoteMessage
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
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

    @Test
    fun test_logReceivedPushMessage_forwardsCorrectCallToLogger() {
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

        pushLogger.logReceivedPushMessage(message, true)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "handleNotificationTrigger: true - Received notification for message: Notification:\n" +
                    "  title = testTitle\n" +
                    "  body = test body\n" +
                    "  icon = testIcon\n" +
                    "  color = testColor\n" +
                    "  imageUrl = null\n" +
                    "Data: {testKey=testValue}\n"
            )
        }
    }

    @Test
    fun test_logReceivedEmptyPushMessage_forwardsCorrectCallToLogger() {
        pushLogger.logReceivedEmptyPushMessage()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Push message received is empty"
            )
        }
    }

    @Test
    fun test_logReceivedCioPushMessage_forwardsCorrectCallToLogger() {
        pushLogger.logReceivedCioPushMessage()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Received CIO push message"
            )
        }
    }

    @Test
    fun test_logReceivedNonCioPushMessage_forwardsCorrectCallToLogger() {
        pushLogger.logReceivedNonCioPushMessage()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Received non CIO push message, ignoring message"
            )
        }
    }

    @Test
    fun test_logReceivedPushMessageWithEmptyDeliveryId_forwardsCorrectCallToLogger() {
        pushLogger.logReceivedPushMessageWithEmptyDeliveryId()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Received message with empty deliveryId"
            )
        }
    }

    @Test
    fun test_logReceivedDuplicatePushMessageDeliveryId_forwardsCorrectCallToLogger() {
        val deliveryId = "delivery-id"
        pushLogger.logReceivedDuplicatePushMessageDeliveryId(deliveryId)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Received duplicate message with deliveryId: $deliveryId"
            )
        }
    }

    @Test
    fun test_logReceivedNewMessageWithDeliveryId_forwardsCorrectCallToLogger() {
        val deliveryId = "delivery-id"
        pushLogger.logReceivedNewMessageWithDeliveryId(deliveryId)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Received new message with deliveryId: $deliveryId"
            )
        }
    }

    @Test
    fun test_logPushMetricsAutoTrackingDisabled_forwardsCorrectCallToLogger() {
        pushLogger.logPushMetricsAutoTrackingDisabled()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Received message but auto tracking is disabled"
            )
        }
    }

    @Test
    fun test_logTrackingPushMessageDelivered_forwardsCorrectCallToLogger() {
        val deliveryId = "delivery-id"
        pushLogger.logTrackingPushMessageDelivered(deliveryId)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Tracking push message delivered with deliveryId: $deliveryId"
            )
        }
    }

    @Test
    fun test_logTrackingPushMessageOpened_forwardsCorrectCallToLogger() {
        val bundle = mockk<Bundle>()
        val payload = CustomerIOParsedPushPayload(
            extras = bundle,
            deepLink = "deepLink",
            cioDeliveryId = "deliveryId",
            cioDeliveryToken = "token",
            title = "title",
            body = "body"
        )
        pushLogger.logTrackingPushMessageOpened(payload)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Tracking push message opened with payload: $payload"
            )
        }
    }

    @Test
    fun test_logFailedToHandlePushClick_forwardsCorrectCallToLogger() {
        val throwable = IllegalArgumentException("error message")
        pushLogger.logFailedToHandlePushClick(throwable)

        assertCalledOnce {
            mockLogger.error(
                tag = "Push",
                message = "Failed to handle push click: error message",
                throwable = throwable
            )
        }
    }

    @Test
    fun test_logHandlingNotificationDeepLink_forwardsCorrectCallToLogger() {
        val payload = mockk<CustomerIOParsedPushPayload>(relaxed = true)
        val behavior = PushClickBehavior.ACTIVITY_NO_FLAGS

        pushLogger.logHandlingNotificationDeepLink(payload, behavior)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Handling push notification deep link with payload: $payload - pushClickBehavior: $behavior"
            )
        }
    }

    @Test
    fun test_logDeepLinkHandledByCallback_forwardsCorrectCallToLogger() {
        pushLogger.logDeepLinkHandledByCallback()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Deep link handled by host app callback implementation"
            )
        }
    }

    @Test
    fun test_logDeepLinkHandledByHostApp_forwardsCorrectCallToLogger() {
        pushLogger.logDeepLinkHandledByHostApp()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Deep link handled by internal host app navigation"
            )
        }
    }

    @Test
    fun test_logDeepLinkHandledExternally_forwardsCorrectCallToLogger() {
        pushLogger.logDeepLinkHandledExternally()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Deep link handled by external app"
            )
        }
    }

    @Test
    fun test_logDeepLinkHandledDefaultHostAppLauncher_forwardsCorrectCallToLogger() {
        pushLogger.logDeepLinkHandledDefaultHostAppLauncher()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Deep link handled by opening default host app"
            )
        }
    }

    @Test
    fun test_logDeepLinkWasNotHandled_forwardsCorrectCallToLogger() {
        pushLogger.logDeepLinkWasNotHandled()

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Deep link was not handled"
            )
        }
    }

    @Test
    fun test_logNotificationActivityStartedWithInvalidIntent_forwardsCorrectCallToLogger() {
        pushLogger.logNotificationActivityStartedWithInvalidIntent()

        assertCalledOnce {
            mockLogger.error(
                tag = "Push",
                message = "Intent is null, cannot process notification click"
            )
        }
    }

    @Test
    fun test_logCreatingNotificationChannel_forwardsCorrectCallToLogger() {
        val channelId = "test_channel_id"
        val channelName = "Test Channel Name"
        val importance = 3 // NotificationManager.IMPORTANCE_DEFAULT

        pushLogger.logCreatingNotificationChannel(channelId, channelName, importance)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Creating new notification channel id: $channelId, name: $channelName, importance: $importance"
            )
        }
    }

    @Test
    fun test_logNotificationChannelAlreadyExists_forwardsCorrectCallToLogger() {
        val channelId = "test_channel_id"

        pushLogger.logNotificationChannelAlreadyExists(channelId)

        assertCalledOnce {
            mockLogger.debug(
                tag = "Push",
                message = "Notification channel already exists id: $channelId"
            )
        }
    }

    @Test
    fun test_logInvalidNotificationChannelImportance_forwardsCorrectCallToLogger() {
        val invalidImportance = 999

        pushLogger.logInvalidNotificationChannelImportance(invalidImportance)

        assertCalledOnce {
            mockLogger.error(
                tag = "Push",
                message = "Notification channel importance level invalid: $invalidImportance"
            )
        }
    }
}
