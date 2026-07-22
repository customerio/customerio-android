package io.customer.messagingpush.livenotification

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.EVENT_LIVE_NOTIFICATION
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.EVENT_LIVE_NOTIFICATION_TOKEN
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.EVENT_TYPE_END
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.EVENT_TYPE_START
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PLATFORM_ANDROID
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_ATTRIBUTES
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_CIO_INSTANCE_ID
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_CONTENT_STATE
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_DEVICE_ID
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_EVENT_TYPE
import io.customer.messagingpush.livenotification.LiveNotificationLifecycleClientImpl.Companion.PROP_NOTIFICATION_TYPE
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
        // The client gates on pipeline.isUserIdentified, which the data pipeline updates
        // synchronously during identify()/clearIdentify() (so it doesn't lag a login).
        every { pipeline.isUserIdentified } returns true
    }

    @Test
    fun reportStart_emitsLiveNotificationWithAttributesAndContentState() {
        identified()
        val name = slot<String>()
        val props = slot<Map<String, Any?>>()
        every { pipeline.track(capture(name), capture(props)) } returns Unit

        client.reportStart(
            instanceUUID = "inst-1",
            activityType = "io.customer.livenotifications.segments",
            deviceId = "fcm-tok",
            attributes = mapOf("header" to "Order update"),
            contentState = mapOf("title" to "On the way")
        )

        name.captured shouldBeEqualTo EVENT_LIVE_NOTIFICATION
        props.captured[PROP_EVENT_TYPE] shouldBeEqualTo EVENT_TYPE_START
        props.captured[PROP_CIO_INSTANCE_ID] shouldBeEqualTo "inst-1"
        props.captured[PROP_DEVICE_ID] shouldBeEqualTo "fcm-tok"
        props.captured[PROP_PLATFORM] shouldBeEqualTo PLATFORM_ANDROID
        props.captured[PROP_NOTIFICATION_TYPE] shouldBeEqualTo "io.customer.livenotifications.segments"
        @Suppress("UNCHECKED_CAST")
        (props.captured[PROP_ATTRIBUTES] as Map<String, Any?>)["header"] shouldBeEqualTo "Order update"
        @Suppress("UNCHECKED_CAST")
        (props.captured[PROP_CONTENT_STATE] as Map<String, Any?>)["title"] shouldBeEqualTo "On the way"
    }

    @Test
    fun reportStart_omitsAttributesAndContentStateWhenEmpty() {
        identified()
        val props = slot<Map<String, Any?>>()
        every { pipeline.track(any(), capture(props)) } returns Unit

        client.reportStart("inst-1", "type", "fcm-tok", attributes = emptyMap(), contentState = emptyMap())

        props.captured.containsKey(PROP_ATTRIBUTES).shouldBeFalse()
        props.captured.containsKey(PROP_CONTENT_STATE).shouldBeFalse()
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
        props.captured[PROP_CIO_INSTANCE_ID] shouldBeEqualTo "inst-9"
        props.captured[PROP_NOTIFICATION_TYPE] shouldBeEqualTo "type-x"
        props.captured[PROP_DEVICE_ID] shouldBeEqualTo "fcm-tok"
        // No final content-state supplied: neither attributes nor contentState are sent.
        props.captured.containsKey(PROP_ATTRIBUTES).shouldBeFalse()
        props.captured.containsKey(PROP_CONTENT_STATE).shouldBeFalse()
    }

    @Test
    fun reportEnd_withFinalContentState_carriesIt() {
        identified()
        val props = slot<Map<String, Any?>>()
        every { pipeline.track(any(), capture(props)) } returns Unit

        client.reportEnd(
            instanceUUID = "inst-9",
            activityType = "type-x",
            deviceId = "fcm-tok",
            contentState = mapOf("title" to "Delivered")
        )

        @Suppress("UNCHECKED_CAST")
        (props.captured[PROP_CONTENT_STATE] as Map<String, Any?>)["title"] shouldBeEqualTo "Delivered"
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
    fun lifecycleEvents_areDroppedForAnonymousUser() {
        every { pipeline.isUserIdentified } returns false

        client.reportStart("inst-1", "type", "fcm-tok", emptyMap(), emptyMap())
        client.reportEnd("inst-1", "type", "fcm-tok")

        verify(exactly = 0) { pipeline.track(any(), any()) }
    }

    @Test
    fun lifecycleEvents_gateOnPipelineIsUserIdentified() {
        // The gate reads pipeline.isUserIdentified directly. The pipeline updates it
        // synchronously during identify(), so an identify() immediately followed by start()
        // is reported rather than dropped by a stale flag.
        every { pipeline.isUserIdentified } returns true
        every { pipeline.track(any(), any()) } returns Unit

        client.reportStart("inst-1", "type", "fcm-tok", emptyMap(), emptyMap())

        verify(exactly = 1) { pipeline.track(EVENT_LIVE_NOTIFICATION, any()) }
    }

    @Test
    fun registerPushToStart_isNotGatedByIdentity() {
        // Identity is gated upstream by LiveNotificationRegistrar; the client must NOT
        // re-check identity for the registration event (that re-introduced the login race).
        every { pipeline.isUserIdentified } returns false
        every { pipeline.track(any(), any()) } returns Unit

        val emitted = client.registerPushToStart("type", "fcm-tok")

        emitted.shouldBeTrue()
        verify(exactly = 1) { pipeline.track(EVENT_LIVE_NOTIFICATION_TOKEN, any()) }
    }

    @Test
    fun events_areDroppedWhenPipelineUnavailable() {
        val noPipeline = LiveNotificationLifecycleClientImpl(dataPipelineProvider = { null })

        val emitted = noPipeline.registerPushToStart("type", "fcm-tok")

        emitted.shouldBeFalse()
    }
}
