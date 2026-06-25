package io.customer.messagingpush.livenotification

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.EVENT_LIVE_NOTIFICATION
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.EVENT_LIVE_NOTIFICATION_TOKEN
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.EVENT_TYPE_END
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.EVENT_TYPE_START
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PLATFORM_ANDROID
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_DEVICE_ID
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_EVENT_TYPE
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_INSTANCE_UUID
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_NOTIFICATION_TYPE
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_PAYLOAD
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_PLATFORM
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_PUSH_TO_START_TOKEN
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_REGISTRATION_TYPE
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.REGISTRATION_TYPE_PUSH_TO_START
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.pipeline.DataPipeline
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(InternalCustomerIOApi::class)
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationLifecycleClientTest : IntegrationTest() {

    private val pipeline: DataPipeline = mockk(relaxed = true)
    private val client = LiveNotificationLifecycleClientImpl(dataPipelineProvider = { pipeline })

    private fun identified() {
        every { pipeline.isUserIdentified } returns true
    }

    @Test
    fun reportStart_emitsLiveNotificationWithStartProperties() {
        identified()
        val name = slot<String>()
        val props = slot<Map<String, Any?>>()
        every { pipeline.track(capture(name), capture(props)) } returns Unit

        client.reportStart(
            instanceUUID = "inst-1",
            activityType = "io.customer.liveactivities.deliverytracking",
            deviceId = "fcm-tok",
            payload = mapOf("orderId" to "A1", "status" to "preparing")
        )

        name.captured shouldBeEqualTo EVENT_LIVE_NOTIFICATION
        props.captured[PROP_EVENT_TYPE] shouldBeEqualTo EVENT_TYPE_START
        props.captured[PROP_INSTANCE_UUID] shouldBeEqualTo "inst-1"
        props.captured[PROP_DEVICE_ID] shouldBeEqualTo "fcm-tok"
        props.captured[PROP_PLATFORM] shouldBeEqualTo PLATFORM_ANDROID
        props.captured[PROP_NOTIFICATION_TYPE] shouldBeEqualTo "io.customer.liveactivities.deliverytracking"
        @Suppress("UNCHECKED_CAST")
        (props.captured[PROP_PAYLOAD] as Map<String, Any?>)["status"] shouldBeEqualTo "preparing"
    }

    @Test
    fun reportStart_omitsPayloadWhenEmpty() {
        identified()
        val props = slot<Map<String, Any?>>()
        every { pipeline.track(any(), capture(props)) } returns Unit

        client.reportStart("inst-1", "type", "fcm-tok", payload = emptyMap())

        props.captured.containsKey(PROP_PAYLOAD).shouldBeFalse()
    }

    @Test
    fun reportEnd_emitsLiveNotificationWithEndProperties() {
        identified()
        val name = slot<String>()
        val props = slot<Map<String, Any?>>()
        every { pipeline.track(capture(name), capture(props)) } returns Unit

        client.reportEnd(instanceUUID = "inst-9", activityType = "type-x", deviceId = "fcm-tok")

        name.captured shouldBeEqualTo EVENT_LIVE_NOTIFICATION
        props.captured[PROP_EVENT_TYPE] shouldBeEqualTo EVENT_TYPE_END
        props.captured[PROP_INSTANCE_UUID] shouldBeEqualTo "inst-9"
        props.captured[PROP_NOTIFICATION_TYPE] shouldBeEqualTo "type-x"
        props.captured[PROP_DEVICE_ID] shouldBeEqualTo "fcm-tok"
        props.captured.containsKey(PROP_PAYLOAD).shouldBeFalse()
    }

    @Test
    fun registerPushToStart_emitsTokenEventWithFcmAsBothIds() {
        identified()
        val name = slot<String>()
        val props = slot<Map<String, Any?>>()
        every { pipeline.track(capture(name), capture(props)) } returns Unit

        val emitted = client.registerPushToStart(activityType = "type-x", deviceId = "fcm-tok")

        emitted.shouldBeTrue()
        name.captured shouldBeEqualTo EVENT_LIVE_NOTIFICATION_TOKEN
        props.captured[PROP_REGISTRATION_TYPE] shouldBeEqualTo REGISTRATION_TYPE_PUSH_TO_START
        props.captured[PROP_PLATFORM] shouldBeEqualTo PLATFORM_ANDROID
        props.captured[PROP_DEVICE_ID] shouldBeEqualTo "fcm-tok"
        props.captured[PROP_PUSH_TO_START_TOKEN] shouldBeEqualTo "fcm-tok"
    }

    @Test
    fun events_areDroppedForAnonymousUser() {
        every { pipeline.isUserIdentified } returns false

        client.reportStart("inst-1", "type", "fcm-tok", emptyMap())
        val emitted = client.registerPushToStart("type", "fcm-tok")

        emitted.shouldBeFalse()
        verify(exactly = 0) { pipeline.track(any(), any()) }
    }

    @Test
    fun events_areDroppedWhenPipelineUnavailable() {
        val noPipeline = LiveNotificationLifecycleClientImpl(dataPipelineProvider = { null })

        val emitted = noPipeline.registerPushToStart("type", "fcm-tok")

        emitted.shouldBeFalse()
    }
}
