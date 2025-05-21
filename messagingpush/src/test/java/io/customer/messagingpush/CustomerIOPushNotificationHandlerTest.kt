package io.customer.messagingpush

import android.os.Bundle
import com.google.firebase.messaging.RemoteMessage
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.extensions.parcelable
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
internal class CustomerIOPushNotificationHandlerTest : IntegrationTest() {
    private lateinit var pushNotificationHandler: CustomerIOPushNotificationHandler
    private lateinit var pushNotificationPayload: CustomerIOParsedPushPayload
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<PushNotificationLogger>(mockPushLogger)
                    }
                }
            }
        )

        val extras = Bundle()
        extras.putString("CIO-Delivery-ID", "anyId")
        extras.putString("CIO-Delivery-Token", "anyToken")
        pushNotificationHandler = CustomerIOPushNotificationHandler(mockk(relaxed = true), RemoteMessage(extras))
        pushNotificationPayload = CustomerIOParsedPushPayload(
            extras = extras,
            deepLink = String.random,
            cioDeliveryId = String.random,
            cioDeliveryToken = String.random,
            title = String.random,
            body = String.random
        )
    }

    @Test
    fun createIntentForNotificationClick_givenAnyPayload_shouldStartNotificationClickReceiverActivity() {
        val actualPendingIntent = pushNotificationHandler.createIntentForNotificationClick(
            contextMock,
            Int.random(1000, 9999),
            pushNotificationPayload
        )

        actualPendingIntent.send()
        val nextStartedActivity = Shadows.shadowOf(applicationMock).nextStartedActivity
        val nextStartedActivityIntent = Shadows.shadowOf(nextStartedActivity)
        val nextStartedActivityPayload: CustomerIOParsedPushPayload? =
            nextStartedActivity.extras?.parcelable(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA)

        nextStartedActivityIntent.intentClass shouldBeEqualTo NotificationClickReceiverActivity::class.java
        nextStartedActivityPayload shouldBeEqualTo pushNotificationPayload
    }

    @Test
    fun handleMessage_shouldLogShowingPushNotification() {
        pushNotificationHandler.handleMessage(contextMock, true)

        assertCalledOnce { mockPushLogger.logShowingPushNotification(any()) }
    }

    @Test
    fun handleMessage_givenNonCioBundle_shouldLogPushMessageEmpty() {
        val bundle = Bundle()
        bundle.putString("anyKey", "anyValue")
        val remoteMessage = RemoteMessage(bundle)
        val handler = CustomerIOPushNotificationHandler(mockk(relaxed = true), remoteMessage)

        handler.handleMessage(contextMock, false)

        assertCalledOnce { mockPushLogger.logReceivedPushMessage(remoteMessage, false) }
        assertCalledOnce { mockPushLogger.logReceivedNonCioPushMessage() }
    }

    @Test
    fun handleMessage_givenEmptyBundle_shouldLogPushMessageEmpty() {
        val bundle = Bundle()
        val remoteMessage = RemoteMessage(bundle)
        val handler = CustomerIOPushNotificationHandler(mockk(relaxed = true), remoteMessage)

        handler.handleMessage(contextMock, true)

        assertCalledOnce { mockPushLogger.logReceivedPushMessage(remoteMessage, true) }
        assertCalledOnce { mockPushLogger.logReceivedEmptyPushMessage() }
    }

    @Test
    fun handleMessage_givenValidCioBundle_shouldLogPushMessageEmpty() {
        val bundle = Bundle()
        bundle.putString("CIO-Delivery-ID", "anyId")
        bundle.putString("CIO-Delivery-Token", "anyToken")
        val remoteMessage = RemoteMessage(bundle)
        val handler = CustomerIOPushNotificationHandler(mockk(relaxed = true), remoteMessage)

        handler.handleMessage(contextMock, false)

        assertCalledOnce { mockPushLogger.logReceivedPushMessage(remoteMessage, false) }
        assertCalledOnce { mockPushLogger.logReceivedCioPushMessage() }
    }

    @Test
    fun handleMessage_givenHandleNotificationTriggerTrue_shouldLogShowingPushNotification() {
        pushNotificationHandler.handleMessage(contextMock, true)

        assertCalledOnce { mockPushLogger.logShowingPushNotification(any()) }
    }

    @Test
    fun handleMessage_givenHandleNotificationTriggerFalse_shouldNotLogShowingPushNotification() {
        pushNotificationHandler.handleMessage(contextMock, false)

        assertCalledNever { mockPushLogger.logShowingPushNotification(any()) }
    }
}
