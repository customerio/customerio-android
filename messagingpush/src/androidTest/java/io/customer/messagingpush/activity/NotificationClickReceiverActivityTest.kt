package io.customer.messagingpush.activity

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.AndroidTest
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.random
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.processor.PushMessageProcessor
import io.customer.sdk.core.di.SDKComponent
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationClickReceiverActivityTest : AndroidTest() {
    private lateinit var pushMessageProcessorMock: PushMessageProcessor

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk { overrideDependency<PushMessageProcessor>(mockk(relaxed = true)) }
                }
            }
        )

        pushMessageProcessorMock = SDKComponent.pushMessageProcessor
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

        assertCalledOnce { pushMessageProcessorMock.processNotificationClick(any(), any()) }
        scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
    }

    @Test
    fun clickNotification_givenSDKNotInitialized_expectProcessPush() {
        val extras = pushActivityExtras()

        val intent = Intent(context, NotificationClickReceiverActivity::class.java)
        intent.putExtras(extras)

        val scenario = ActivityScenario.launch<NotificationClickReceiverActivity>(intent)

        assertCalledOnce { pushMessageProcessorMock.processNotificationClick(any(), any()) }
        scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
    }

    @Test
    fun clickNotification_givenNullIntent_expectDoNoProcessPush() {
        val intent = Intent(context, NotificationClickReceiverActivity::class.java)

        val scenario = ActivityScenario.launch<NotificationClickReceiverActivity>(intent)

        assertNoInteractions(pushMessageProcessorMock)
        scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
    }
}
