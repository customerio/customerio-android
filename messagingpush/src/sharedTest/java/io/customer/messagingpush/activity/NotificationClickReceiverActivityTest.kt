package io.customer.messagingpush.activity

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseIntegrationTest
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.processor.PushMessageProcessor
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.extensions.random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

@RunWith(AndroidJUnit4::class)
class NotificationClickReceiverActivityTest : BaseIntegrationTest() {
    private val pushMessageProcessorMock: PushMessageProcessor = mock()

    @Before
    override fun setup() {
        super.setup()

        di.overrideDependency(PushMessageProcessor::class.java, pushMessageProcessorMock)
    }

    private fun pushActivityExtras(): Bundle {
        val payload = CustomerIOParsedPushPayload(
            extras = Bundle.EMPTY,
            deepLink = null,
            cioDeliveryId = String.random,
            cioDeliveryToken = String.random,
            title = String.random,
            body = String.random
        )

        return Bundle().apply {
            putParcelable(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, payload)
        }
    }

    @Test
    fun clickNotification_givenValidIntent_expectProcessPush() {
        val extras = pushActivityExtras()
        val intent = Intent(context, NotificationClickReceiverActivity::class.java)
        intent.putExtras(extras)

        val scenario = ActivityScenario.launch<NotificationClickReceiverActivity>(intent)

        verify(pushMessageProcessorMock).processNotificationClick(any(), any())
        scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
    }

    @Test
    fun clickNotification_givenSDKNotInitialized_expectDoNoProcessPush() {
        val extras = pushActivityExtras()

        val intent = Intent(context, NotificationClickReceiverActivity::class.java)
        intent.putExtras(extras)

        CustomerIO.clearInstance()
        val scenario = ActivityScenario.launch<NotificationClickReceiverActivity>(intent)

        verifyNoInteractions(pushMessageProcessorMock)
        scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
    }

    @Test
    fun clickNotification_givenNullIntent_expectDoNoProcessPush() {
        val intent = Intent(context, NotificationClickReceiverActivity::class.java)

        val scenario = ActivityScenario.launch<NotificationClickReceiverActivity>(intent)

        verifyNoInteractions(pushMessageProcessorMock)
        scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
    }
}
