package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.livenotification.template.TemplateRegistry
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
 * Covers the host-app render override (`createLiveNotification`) and
 * customer-defined activity types.
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationCallbackTest : IntegrationTest() {

    private val notificationManager: NotificationManager = mockk(relaxed = true)
    private val customType = "com.acme.live.ride"

    private fun attach(callback: CustomerIOPushNotificationCallback?) {
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder().apply {
                callback?.let { setNotificationCallback(it) }
                registerLiveNotificationTypes(customType)
            }.build()
        ).attachToSDKComponent()
    }

    private fun callbackReturning(notification: Notification) = object : CustomerIOPushNotificationCallback {
        override fun createLiveNotification(payload: CustomerIOParsedPushPayload, context: Context): Notification =
            notification
    }

    private fun appNotification(title: String): Notification =
        NotificationCompat.Builder(contextMock, "channel").setSmallIcon(0).setContentTitle(title).build()

    private fun bundle(activityType: String): Bundle = Bundle().apply {
        putString(LiveNotificationHandler.ACTIVITY_ID_KEY, "act-cb")
        putString(LiveNotificationHandler.EVENT_KEY, "start")
        putString(LiveNotificationHandler.ACTIVITY_TYPE_KEY, activityType)
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

        invoke(bundle(TemplateRegistry.DELIVERY_TRACKING))

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
        attach(callback = null) // registered type, but no renderer

        invoke(bundle(customType))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }
}
