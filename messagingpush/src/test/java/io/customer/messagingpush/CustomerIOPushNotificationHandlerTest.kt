package io.customer.messagingpush

import android.os.Bundle
import com.google.firebase.messaging.RemoteMessage
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.extensions.parcelable
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

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        val extras = Bundle.EMPTY
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
}
