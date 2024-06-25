package io.customer.messagingpush

import android.os.Bundle
import io.customer.messagingpush.support.core.JUnitTest
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.messagingpush.util.PushTrackingUtilImpl
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.extensions.random
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBe
import org.junit.jupiter.api.Test

class PushTrackingUtilTest : JUnitTest() {

    private lateinit var eventBus: EventBus
    private lateinit var util: PushTrackingUtil

    override fun setupTestEnvironment() {
        util = PushTrackingUtilImpl()
        eventBus = mockk(relaxed = true)

        super.setupTestEnvironment()
    }

    override fun setupSDKComponent() {
        super.setupSDKComponent()

        SDKComponent.overrideDependency(EventBus::class.java, eventBus)
    }

    @Test
    fun parseLaunchedActivityForTracking_givenBundleWithoutDeliveryData_expectDoNoTrackPush() {
        val givenBundle = mockk<Bundle>()
        every { givenBundle.getString("foo") } returns "randomString"
        every { givenBundle.getString(PushTrackingUtil.DELIVERY_ID_KEY) } returns null
        every { givenBundle.getString(PushTrackingUtil.DELIVERY_TOKEN_KEY) } returns null

        val result = util.parseLaunchedActivityForTracking(givenBundle)

        result shouldBe false

        verify(exactly = 0) { eventBus.publish(any<Event.TrackPushMetricEvent>()) }
    }

    @Test
    fun parseLaunchedActivityForTracking_givenBundleWithDeliveryData_expectTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random

        val givenBundle = mockk<Bundle>()
        every { givenBundle.getString(PushTrackingUtil.DELIVERY_ID_KEY) } returns givenDeliveryId
        every { givenBundle.getString(PushTrackingUtil.DELIVERY_TOKEN_KEY) } returns givenDeviceToken

        val result = util.parseLaunchedActivityForTracking(givenBundle)

        result shouldBe true

        verify(exactly = 1) {
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    deliveryId = givenDeliveryId,
                    event = MetricEvent.opened.name,
                    deviceToken = givenDeviceToken
                )
            )
        }
    }
}
