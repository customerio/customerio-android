package io.customer.messagingpush

import android.app.PendingIntent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.messaging.RemoteMessage
import io.customer.commontest.BaseTest
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.extensions.parcelable
import io.customer.sdk.extensions.random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
internal class CustomerIOPushNotificationHandlerTest : BaseTest() {

    private lateinit var pushNotificationHandler: CustomerIOPushNotificationHandler
    private lateinit var pushNotificationPayload: CustomerIOParsedPushPayload

    @Before
    override fun setup() {
        super.setup()

        val extras = Bundle.EMPTY
        pushNotificationHandler = CustomerIOPushNotificationHandler(mock(), RemoteMessage(extras))
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
    @Config(sdk = [android.os.Build.VERSION_CODES.LOLLIPOP])
    fun createIntentForNotificationClick_preAndroidM_shouldNotSetImmutableFlag() {
        val actualPendingIntent = pushNotificationHandler.createIntentForNotificationClick(
            context,
            Int.random(1000, 9999),
            pushNotificationPayload
        )

        val expectedIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT
        Shadows.shadowOf(actualPendingIntent).flags shouldBeEqualTo expectedIntentFlags
    }

    @Test
    @Config(sdk = [android.os.Build.VERSION_CODES.TIRAMISU])
    fun createIntentForNotificationClick_androidMOrHigher_shouldSetImmutableFlag() {
        val actualPendingIntent = pushNotificationHandler.createIntentForNotificationClick(
            context,
            Int.random(1000, 9999),
            pushNotificationPayload
        )

        val expectedIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        Shadows.shadowOf(actualPendingIntent).flags shouldBeEqualTo expectedIntentFlags
    }

    @Test
    fun createIntentForNotificationClick_validPayload_shouldStartDeepLinkedActivity() {
        val actualPendingIntent = pushNotificationHandler.createIntentForNotificationClick(
            context,
            Int.random(1000, 9999),
            pushNotificationPayload
        )

        actualPendingIntent.send()
        val nextStartedActivity = Shadows.shadowOf(application).nextStartedActivity
        val nextStartedActivityIntent = Shadows.shadowOf(nextStartedActivity)
        val nextStartedActivityPayload: CustomerIOParsedPushPayload? =
            nextStartedActivity.extras?.parcelable(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA)

        nextStartedActivityIntent.intentClass shouldBeEqualTo NotificationClickReceiverActivity::class.java
        nextStartedActivityPayload shouldBeEqualTo pushNotificationPayload
    }
}
