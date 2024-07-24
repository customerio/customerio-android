package io.customer.messagingpush

import android.os.Bundle
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.messagingpush.util.PushTrackingUtilImpl
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.events.Metric
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.junit.jupiter.api.Test

class PushTrackingUtilTest : JUnitTest() {
    private lateinit var eventBus: EventBus
    private lateinit var pushTrackingUtil: PushTrackingUtil

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk { overrideDependency<EventBus>(mockk(relaxed = true)) }
                }
            }
        )

        eventBus = SDKComponent.eventBus
        pushTrackingUtil = PushTrackingUtilImpl()
    }

    @Test
    fun parseLaunchedActivityForTracking_givenBundleWithoutDeliveryData_expectDoNoTrackPush() {
        val givenBundle = mockk<Bundle>()
        every { givenBundle.getString("foo") } returns "randomString"
        every { givenBundle.getString(PushTrackingUtil.DELIVERY_ID_KEY) } returns null
        every { givenBundle.getString(PushTrackingUtil.DELIVERY_TOKEN_KEY) } returns null

        val result = pushTrackingUtil.parseLaunchedActivityForTracking(givenBundle)

        result shouldBe false

        assertCalledNever { eventBus.publish(any<Event.TrackPushMetricEvent>()) }
    }

    @Test
    fun parseLaunchedActivityForTracking_givenBundleWithDeliveryData_expectTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random

        val givenBundle = mockk<Bundle>()
        every { givenBundle.getString(PushTrackingUtil.DELIVERY_ID_KEY) } returns givenDeliveryId
        every { givenBundle.getString(PushTrackingUtil.DELIVERY_TOKEN_KEY) } returns givenDeviceToken

        val result = pushTrackingUtil.parseLaunchedActivityForTracking(givenBundle)

        result shouldBe true

        assertCalledOnce {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Opened,
                    deliveryId = givenDeliveryId,
                    deviceToken = givenDeviceToken
                )
            )
        }
    }
}
