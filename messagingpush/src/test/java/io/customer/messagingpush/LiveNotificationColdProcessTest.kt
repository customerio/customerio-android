package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Color
import android.os.Bundle
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.livenotification.LiveNotificationBrandingSerializer
import io.customer.messagingpush.livenotification.LiveNotificationType
import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rendering in a **cold process** — one Android started solely to deliver an FCM message, where no
 * app code ran and `CustomerIO.initialize` never registered the push module.
 *
 * The distinguishing setup is what these tests *don't* do: unlike [LiveNotificationHandlerTest] they
 * never call `attachToSDKComponent()`, so `SDKComponent.pushModuleConfig` resolves to
 * [MessagingPushModuleConfig.default] exactly as it does in a real cold process. Everything the
 * render needs must therefore come from [io.customer.messagingpush.livenotification.LiveNotificationStore].
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationColdProcessTest : IntegrationTest() {

    private val notificationManager: NotificationManager = mockk(relaxed = true)
    private val channelId = "live-notifications"

    private fun newBundle(
        activityId: String = "live-act-1",
        event: String = "start",
        activityType: String? = TemplateRegistry.SEGMENTS
    ): Bundle = Bundle().apply {
        putString(LiveNotificationHandler.CIO_INSTANCE_ID_KEY, activityId)
        putString(LiveNotificationHandler.EVENT_KEY, event)
        if (activityType != null) {
            putString(LiveNotificationHandler.NOTIFICATION_TYPE_KEY, activityType)
        }
        // Minimal renderable content for either built-in template.
        val data = JSONObject().apply {
            put("status", "Out for delivery")
            put("title", "Out for delivery")
        }
        for (key in data.keys()) putString(key, data.get(key).toString())
    }

    private fun invoke(bundle: Bundle) {
        LiveNotificationHandler(bundle).handle(
            context = contextMock,
            deliveryId = "delivery-id-1",
            deliveryToken = "delivery-token-1",
            smallIcon = 0,
            tintColor = null,
            channelId = channelId,
            notificationManager = notificationManager
        )
    }

    @Test
    fun handle_givenPersistedOptInAndNoModuleConfig_rendersNotification() {
        // The regression: before the persisted opt-in existed, the empty default config made this
        // push indistinguishable from one sent to an app that never enabled live notifications, and
        // it was dropped silently.
        SDKComponent.liveNotificationStore.setEnabledActivityTypes(setOf(TemplateRegistry.SEGMENTS))

        invoke(newBundle())

        verify { notificationManager.notify("live-act-1", any<Int>(), any<Notification>()) }
    }

    @Test
    fun handle_givenNoPersistedOptInAndNoModuleConfig_dropsNotification() {
        // Unchanged behaviour for an app that genuinely never enabled live notifications: an
        // unsolicited push must never render.
        invoke(newBundle())

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenPopulatedConfigExcludingType_dropsEvenWhenPersistedSetContainsIt() {
        // A populated config always wins, so *disabling* a type takes effect immediately in a
        // running process instead of waiting for the persisted copy to be rewritten.
        SDKComponent.liveNotificationStore.setEnabledActivityTypes(setOf(TemplateRegistry.SEGMENTS))
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder()
                .enableLiveNotificationTypes(LiveNotificationType.COUNTDOWN_TIMER)
                .build()
        ).attachToSDKComponent()

        invoke(newBundle(activityType = TemplateRegistry.SEGMENTS))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenColdProcessEnd_postsTerminalStateRatherThanStrandingIt() {
        // The worst symptom of the drop: an `end` delivered to a cold process left the ongoing
        // notification a previous process posted on screen forever — and an ongoing notification is
        // not user-dismissible before Android 14, so there was no way to clear it.
        SDKComponent.liveNotificationStore.setEnabledActivityTypes(setOf(TemplateRegistry.SEGMENTS))

        invoke(newBundle(event = "end"))

        verify { notificationManager.notify("live-act-1", any<Int>(), any<Notification>()) }
    }

    @Test
    fun handle_givenPersistedBranding_appliesItToAColdRender() {
        // Branding lives in the same in-memory config as the enabled types, so without persisting it
        // a cold render would swap the branded status-bar icon for the app's manifest icon halfway
        // through an ongoing notification's life.
        val brandedSmallIcon = android.R.drawable.ic_dialog_info
        val store = SDKComponent.liveNotificationStore
        store.setEnabledActivityTypes(setOf(TemplateRegistry.SEGMENTS))
        store.setBrandingJson(
            LiveNotificationBrandingSerializer.encode(
                context = contextMock,
                branding = LiveNotificationBranding(
                    companyName = "Acme",
                    accentColor = Color.RED,
                    smallIcon = brandedSmallIcon
                )
            )
        )
        val posted = slot<Notification>()
        every { notificationManager.notify(any<String>(), any<Int>(), capture(posted)) } returns Unit

        invoke(newBundle())

        // The legacy int `icon` field mirrors the resId passed to setSmallIcon.
        @Suppress("DEPRECATION")
        posted.captured.icon shouldBeEqualTo brandedSmallIcon
    }
}
