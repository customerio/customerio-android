package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.data.communication.CustomerIOLiveNotificationsCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.livenotification.LiveNotificationType
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the host-app render override ([CustomerIOLiveNotificationsCallback]) and
 * customer-defined activity types. Live-notification rendering is deliberately a
 * separate callback from `CustomerIOPushNotificationCallback`, so it is registered
 * via `setLiveNotificationCallback`, not `setNotificationCallback`.
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationCallbackTest : IntegrationTest() {

    private val notificationManager: NotificationManager = mockk(relaxed = true)
    private val customType = "com.acme.live.ride"

    private fun attach(callback: CustomerIOLiveNotificationsCallback?) {
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder().apply {
                callback?.let { setLiveNotificationCallback(it) }
                // Enable a built-in type (for the override test) and the custom type.
                enableLiveNotificationTypes(LiveNotificationType.SEGMENTS)
                enableCustomLiveNotificationTypes(customType)
            }.build()
        ).attachToSDKComponent()
    }

    private fun callbackReturning(notification: Notification) = object : CustomerIOLiveNotificationsCallback {
        override fun createLiveNotification(payload: CustomerIOParsedPushPayload, context: Context): Notification =
            notification
    }

    private fun appNotification(title: String): Notification =
        NotificationCompat.Builder(contextMock, "channel").setSmallIcon(0).setContentTitle(title).build()

    private fun bundle(activityType: String, event: String = "start"): Bundle = Bundle().apply {
        putString(LiveNotificationHandler.CIO_INSTANCE_ID_KEY, "act-cb")
        putString(LiveNotificationHandler.EVENT_KEY, event)
        putString(LiveNotificationHandler.NOTIFICATION_TYPE_KEY, activityType)
    }

    private fun invoke(b: Bundle) = LiveNotificationHandler(b).handle(
        context = contextMock,
        deliveryId = "d",
        deliveryToken = "t",
        smallIcon = 0,
        tintColor = null,
        channelId = "channel",
        notificationManager = notificationManager
    )

    @Test
    fun builtInType_callbackReturningNotification_isPostedInsteadOfTemplate() {
        val custom = appNotification("App rendered")
        attach(callbackReturning(custom))
        val posted = slot<Notification>()
        every { notificationManager.notify(any<String>(), any<Int>(), capture(posted)) } returns Unit

        invoke(bundle(LiveNotificationType.SEGMENTS.identifier))

        posted.captured shouldBeEqualTo custom
    }

    @Test
    fun customType_withCallback_isRendered() {
        val custom = appNotification("Custom render")
        attach(callbackReturning(custom))

        invoke(bundle(customType))

        verify(exactly = 1) {
            notificationManager.notify("act-cb", any<Int>(), custom)
        }
    }

    @Test
    fun customType_withoutCallback_isDropped() {
        attach(callback = null) // enabled type, but no renderer

        invoke(bundle(customType))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun throwingCallback_onPushPath_isContainedAndPostsNothing() {
        // The FCM path runs on FirebaseMessagingService.onMessageReceived, which has no
        // try/catch of its own, so a throwing host renderer would take the process down.
        // Containment lives in LiveNotificationHandler.handle and therefore covers this
        // path as well as the local one — matching what the callback KDoc promises.
        attach(
            object : CustomerIOLiveNotificationsCallback {
                override fun createLiveNotification(
                    payload: CustomerIOParsedPushPayload,
                    context: Context
                ): Notification = throw IllegalStateException("app renderer blew up")
            }
        )

        // Must not propagate (invoke() drives the push path: bypassOrderGuard = false).
        invoke(bundle(customType))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun customType_endWithoutRenderer_cancelsSinceThereIsNoEndState() {
        // Custom type, no callback → no end-state to post. With nothing to show for the
        // end, the SDK cancels the notification rather than leaving the prior ongoing
        // one stuck and non-dismissible.
        attach(callback = null)
        val expectedNotifId = "act-cb".hashCode() and 0x7FFFFFFF

        invoke(bundle(customType, event = "end"))

        verify(exactly = 1) {
            notificationManager.cancel("act-cb", expectedNotifId)
        }
    }
}
