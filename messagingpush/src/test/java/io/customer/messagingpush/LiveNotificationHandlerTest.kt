package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.livenotification.LiveNotificationAsset
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.livenotification.LiveNotificationType
import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [LiveNotificationHandler] focused on envelope parsing and dispatch:
 *
 * - top-level wire keys (`cioInstanceId`, `event`, `notification_type`, `timestamp`)
 *   are read from the [Bundle];
 * - template fields arrive flattened at the envelope top level or nested under
 *   a `payload` object;
 * - missing `cioInstanceId`, `event`, or unknown `notification_type` are dropped
 *   without posting a notification;
 * - `event = "end"` posts the final state and leaves it visible for the user to dismiss.
 *
 * The actual rendered notification is opaque to these tests — that's covered by
 * the per-template render tests. Here we only assert the dispatch contract.
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationHandlerTest : IntegrationTest() {

    private val notificationManager: NotificationManager = mockk(relaxed = true)
    private val channelId = "live-notifications"

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        // Live notifications are opt-in; enable all built-in types so the dispatch tests run.
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder().enableLiveNotificationTypes(
                LiveNotificationType.SEGMENTS,
                LiveNotificationType.COUNTDOWN_TIMER
            ).build()
        ).attachToSDKComponent()
    }

    private fun newBundle(
        activityId: String? = "live-act-1",
        event: String? = "start",
        activityType: String? = TemplateRegistry.SEGMENTS,
        // Minimal renderable content: Segments treats `status` as required and CountdownTimer
        // treats `title` as required, so supplying both means envelope/ordering tests post a
        // notification for either template rather than being dropped by the "no usable content"
        // guard (which is exercised separately).
        data: JSONObject = JSONObject().apply {
            put("status", "Status")
            put("title", "Status")
        },
        timestamp: Long? = null
    ): Bundle {
        val bundle = Bundle()
        if (activityId != null) bundle.putString(LiveNotificationHandler.CIO_INSTANCE_ID_KEY, activityId)
        if (event != null) bundle.putString(LiveNotificationHandler.EVENT_KEY, event)
        if (activityType != null) bundle.putString(LiveNotificationHandler.NOTIFICATION_TYPE_KEY, activityType)
        if (timestamp != null) bundle.putString(LiveNotificationHandler.TIMESTAMP_KEY, timestamp.toString())
        // Template fields ride flattened at the top level, as the backend delivers them.
        for (key in data.keys()) bundle.putString(key, data.get(key).toString())
        return bundle
    }

    private fun handlerFor(bundle: Bundle): LiveNotificationHandler = LiveNotificationHandler(bundle)

    private fun invoke(handler: LiveNotificationHandler, bypassOrderGuard: Boolean = false) {
        handler.handle(
            context = contextMock,
            deliveryId = "delivery-id-1",
            deliveryToken = "delivery-token-1",
            smallIcon = 0,
            tintColor = null,
            channelId = channelId,
            notificationManager = notificationManager,
            bypassOrderGuard = bypassOrderGuard
        )
    }

    // --- Envelope keys are exactly as documented ---

    @Test
    fun envelopeKeys_areTheCrossPlatformSpecKeys() {
        // Lock the wire-format constants so any future rename surfaces here.
        // Failure to update both the SDK and CIO backend would silently break live notifications.
        LiveNotificationHandler.CIO_INSTANCE_ID_KEY shouldBeEqualTo "cioInstanceId"
        LiveNotificationHandler.EVENT_KEY shouldBeEqualTo "event"
        LiveNotificationHandler.NOTIFICATION_TYPE_KEY shouldBeEqualTo "notification_type"
        LiveNotificationHandler.TIMESTAMP_KEY shouldBeEqualTo "timestamp"
    }

    // --- Happy-path dispatch ---

    @Test
    fun handle_givenBothTemplates_postsNotificationForEach() {
        val templates = listOf(
            TemplateRegistry.SEGMENTS,
            TemplateRegistry.COUNTDOWN_TIMER
        )
        for (activityType in templates) {
            invoke(handlerFor(newBundle(activityType = activityType)))
        }

        verify(exactly = templates.size) {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_postsNotificationKeyedByActivityIdHash() {
        val activityId = "live-activity-id-xyz"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val data = JSONObject().apply {
            put("status", "Out for delivery")
            put("substatus", "For User")
            put("segmentsTotal", 4)
            put("segmentsComplete", 2)
        }
        val bundle = newBundle(activityId = activityId, data = data)

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
    }

    @Test
    fun handle_givenUpdateEvent_postsNotificationInPlace() {
        // A server-pushed `update` re-renders the activity (same id) rather than being dropped.
        // It is NOT reported as a lifecycle event — the backend initiated it, so it already knows.
        val activityId = "live-activity-update"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(activityId = activityId, event = "update")

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
    }

    @Test
    fun handle_givenTemplateFieldsNestedUnderPayload_unwrapsAndPosts() {
        // Backend delivers template fields nested under a `payload` object (JSON string),
        // not flattened. The handler must unwrap them so the template renders.
        val activityId = "payload-nested"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(activityId = activityId, data = JSONObject()).apply {
            putString(
                LiveNotificationHandler.PAYLOAD_KEY,
                JSONObject().apply {
                    put("status", "preparing")
                    put("substatus", "order abc-123")
                    put("segmentsTotal", 4)
                    put("segmentsComplete", 1)
                }.toString()
            )
        }

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
    }

    @Test
    fun handle_givenTemplateFieldNamedTitle_isNotStrippedAsReservedKey() {
        // Regression: "title" is a CountdownTimer template field and must reach the
        // template, not be treated as the standard-push reserved key and dropped.
        val posted = slot<Notification>()
        every { notificationManager.notify(any<String>(), any<Int>(), capture(posted)) } returns Unit

        val data = JSONObject().apply {
            put("title", "Flash Sale")
            // Epoch SECONDS on the wire (60s ahead), not millis.
            put("endTime", System.currentTimeMillis() / 1000 + 60L)
            put("statusMessage", "Sale starts in")
        }
        invoke(handlerFor(newBundle(activityType = TemplateRegistry.COUNTDOWN_TIMER, data = data)))

        posted.captured.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() shouldBeEqualTo "Flash Sale"
    }

    @Test
    fun handle_givenNestedJsonFieldAsString_parsesAndPosts() {
        // Nested objects can arrive as JSON strings in FCM data; the handler parses them into
        // JSON containers. The 2 built-in templates read only flat fields, so a stray nested
        // object is simply ignored — but parsing it must not break rendering.
        val data = JSONObject().apply {
            put("status", "On the way")
            put("segmentsTotal", 3)
            put("segmentsComplete", 1)
            put("extra", JSONObject().put("ignored", "value"))
        }
        val bundle = newBundle(activityType = TemplateRegistry.SEGMENTS, data = data)

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    // --- Branding (small icon + large-icon logo) ---

    @Test
    fun handle_givenBrandingLogo_rendersLogoAsLargeIcon() {
        // Segments is branding-only (sets no largeIcon of its own), so the handler fills the
        // color large-icon slot from branding.logo — a strongly-typed LiveNotificationAsset.
        attachBranding(
            LiveNotificationBranding(
                companyName = "Acme",
                accentColor = 0xFF00FF00.toInt(),
                logo = LiveNotificationAsset.Bytes(byteArrayOf(1, 2, 3, 4))
            )
        )
        val posted = slot<Notification>()
        every { notificationManager.notify(any<String>(), any<Int>(), capture(posted)) } returns Unit

        invoke(handlerFor(newBundle()))

        posted.captured.getLargeIcon().shouldNotBeNull()
    }

    @Test
    fun handle_givenBrandingSmallIcon_overridesFallback() {
        // invoke() passes fallback smallIcon = 0; branding.smallIcon must override it.
        val brandedSmallIcon = android.R.drawable.ic_dialog_info
        attachBranding(
            LiveNotificationBranding(
                companyName = "Acme",
                accentColor = 0xFF00FF00.toInt(),
                smallIcon = brandedSmallIcon
            )
        )
        val posted = slot<Notification>()
        every { notificationManager.notify(any<String>(), any<Int>(), capture(posted)) } returns Unit

        invoke(handlerFor(newBundle()))

        // The legacy int `icon` field mirrors the resId passed to setSmallIcon.
        @Suppress("DEPRECATION")
        posted.captured.icon shouldBeEqualTo brandedSmallIcon
    }

    private fun attachBranding(branding: LiveNotificationBranding) {
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder()
                .enableLiveNotificationTypes(
                    LiveNotificationType.SEGMENTS,
                    LiveNotificationType.COUNTDOWN_TIMER
                )
                .setLiveNotificationBranding(branding)
                .build()
        ).attachToSDKComponent()
    }

    // --- Missing required fields short-circuit ---

    @Test
    fun handle_givenMissingActivityId_returnsEarlyWithoutNotifying() {
        invoke(handlerFor(newBundle(activityId = null)))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenMissingEvent_dropsAndDoesNotNotify() {
        // event is required — there is no implicit "update" default.
        invoke(handlerFor(newBundle(event = null)))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenStartWithNoContentFields_doesNotPostEmptyNotification() {
        // Enabled type + valid envelope, but the template fields never arrived (e.g. content
        // wasn't flattened). The template can't render anything meaningful, so we must NOT post
        // a blank notification.
        val bundle = newBundle(
            activityId = "no-content",
            event = "start",
            activityType = TemplateRegistry.SEGMENTS,
            data = JSONObject()
        )

        invoke(handlerFor(bundle))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenMissingActivityType_dropsAndDoesNotNotify() {
        invoke(handlerFor(newBundle(activityType = null)))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenUnknownActivityType_dropsAndDoesNotNotify() {
        invoke(handlerFor(newBundle(activityType = "io.customer.livenotifications.bogus")))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenBareTemplateNameWithoutSpecPrefix_dropsAndDoesNotNotify() {
        // The cross-platform spec requires the `io.customer.livenotifications.` prefix.
        // Bare names like "segments" must be rejected to stay aligned with iOS.
        invoke(handlerFor(newBundle(activityType = "segments")))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    // --- End event: final state stays posted and dismissible ---

    @Test
    fun handle_givenEventEnd_postsEndStateAndLeavesVisible() {
        val activityId = "ending-activity"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(activityId = activityId, event = "end")

        invoke(handlerFor(bundle))

        // The end-state is posted and must REMAIN visible for the user to swipe away — the SDK
        // never auto-removes an ended activity (matching iOS, which leaves it on screen).
        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
        assertCalledNever {
            notificationManager.cancel(activityId, expectedNotifId)
        }
    }

    @Test
    fun handle_givenEventEnd_postsUserDismissibleNotification() {
        val activityId = "ending-dismissible"
        val posted = slot<Notification>()
        every { notificationManager.notify(any<String>(), any<Int>(), capture(posted)) } returns Unit
        val bundle = newBundle(activityId = activityId, event = "end")

        invoke(handlerFor(bundle))

        // Non-ongoing so a swipe removes it; auto-cancel so a tap clears it.
        (posted.captured.flags and Notification.FLAG_ONGOING_EVENT) shouldBeEqualTo 0
        (posted.captured.flags and Notification.FLAG_AUTO_CANCEL) shouldBeEqualTo Notification.FLAG_AUTO_CANCEL
    }

    @Test
    fun handle_givenEndWithNoRenderableContent_cancelsNotification() {
        // An `end` whose final state can't render (empty template fields) has no end-state
        // to show, so the prior (ongoing) notification is cancelled rather than left stuck.
        val activityId = "empty-end"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF

        invoke(handlerFor(newBundle(activityId = activityId, event = "end", data = JSONObject())))

        verify(exactly = 1) { notificationManager.cancel(activityId, expectedNotifId) }
        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenEventStart_doesNotCancel() {
        val activityId = "starting-activity"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(activityId = activityId, event = "start")

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
        assertCalledNever {
            notificationManager.cancel(activityId, expectedNotifId)
        }
    }

    // --- Out-of-order / duplicate guard ---

    @Test
    fun handle_givenOlderTimestamp_dropsTheStalePush() {
        val activityId = "ooo-activity"

        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 100L)))
        // Arrives late and is older than what was already rendered: must be dropped.
        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 50L)))

        verify(exactly = 1) {
            notificationManager.notify(activityId, any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenLocalBypass_rendersDespiteNonAdvancingTimestamp() {
        // Local renders are host-ordered and bypass the guard, so two updates within
        // the same wall-clock second (same epoch-second timestamp) both post, instead
        // of the second being dropped as a duplicate.
        val activityId = "local-bypass"

        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 100L)), bypassOrderGuard = true)
        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 100L)), bypassOrderGuard = true)

        verify(exactly = 2) {
            notificationManager.notify(activityId, any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenNewerTimestamp_rendersBoth() {
        val activityId = "in-order-activity"

        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 100L)))
        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 200L)))

        verify(exactly = 2) {
            notificationManager.notify(activityId, any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenStaleEndTimestamp_stillRendersEndState() {
        // `end` is terminal and bypasses the out-of-order guard, so it still renders its
        // final state even if its timestamp is not newer than the last update.
        val activityId = "stale-end"

        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 100L)))
        invoke(handlerFor(newBundle(activityId = activityId, event = "end", timestamp = 50L)))

        // Both the update and the stale end post (2 notifies); the SDK never cancels on end.
        verify(exactly = 2) {
            notificationManager.notify(activityId, any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenStaleUpdateAfterEnd_isDropped() {
        // `end` records its timestamp as the high-water mark (not cleared), so a delayed
        // older update arriving after `end` is dropped rather than resurrecting it.
        val activityId = "update-after-end"

        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 100L)))
        invoke(handlerFor(newBundle(activityId = activityId, event = "end", timestamp = 200L)))
        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 150L)))

        // Only the first update and the end posted; the stale 150 update was dropped.
        verify(exactly = 2) {
            notificationManager.notify(activityId, any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenStaleEndThenStaleUpdate_doesNotResurrect() {
        // A stale, out-of-order `end` (lower timestamp) bypasses the guard to cancel, but
        // must NOT lower the high-water mark; otherwise a later stale update could slip
        // through and resurrect the cancelled activity.
        val activityId = "stale-end-then-update"

        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 100L)))
        invoke(handlerFor(newBundle(activityId = activityId, event = "end", timestamp = 50L)))
        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 75L)))

        // Mark stays at 100, so the 75 update is dropped: only the first update and the end posted.
        verify(exactly = 2) {
            notificationManager.notify(activityId, any<Int>(), any<Notification>())
        }
    }
}
