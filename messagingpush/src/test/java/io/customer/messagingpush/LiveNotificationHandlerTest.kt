package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import io.customer.commontest.extensions.assertCalledNever
import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper

/**
 * Tests for [LiveNotificationHandler] focused on envelope parsing and dispatch:
 *
 * - top-level wire keys (`activity_id`, `event`, `activity_type`, `attributes`,
 *   `content_state`) are read from the [Bundle];
 * - `attributes` and `content_state` are decoded into independent JSONObjects;
 * - unknown / missing `activity_type` and `activity_id` are dropped without
 *   posting a notification;
 * - `event = "end"` schedules a delayed cancel.
 *
 * The actual rendered notification is opaque to these tests — that's covered by
 * the per-template render tests. Here we only assert the dispatch contract.
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationHandlerTest : IntegrationTest() {

    private val notificationManager: NotificationManager = mockk(relaxed = true)
    private val channelId = "live-notifications"

    private fun newBundle(
        activityId: String? = "live-act-1",
        event: String? = "start",
        activityType: String? = TemplateRegistry.DELIVERY_TRACKING,
        attributesJson: String? = "{}",
        contentStateJson: String? = "{}"
    ): Bundle {
        val bundle = Bundle()
        if (activityId != null) bundle.putString(LiveNotificationHandler.ACTIVITY_ID_KEY, activityId)
        if (event != null) bundle.putString(LiveNotificationHandler.EVENT_KEY, event)
        if (activityType != null) bundle.putString(LiveNotificationHandler.ACTIVITY_TYPE_KEY, activityType)
        if (attributesJson != null) bundle.putString(LiveNotificationHandler.ATTRIBUTES_KEY, attributesJson)
        if (contentStateJson != null) bundle.putString(LiveNotificationHandler.CONTENT_STATE_KEY, contentStateJson)
        return bundle
    }

    private fun handlerFor(bundle: Bundle): LiveNotificationHandler = LiveNotificationHandler(bundle)

    private fun invoke(handler: LiveNotificationHandler) {
        handler.handle(
            context = contextMock,
            deliveryId = "delivery-id-1",
            deliveryToken = "delivery-token-1",
            smallIcon = 0,
            tintColor = null,
            channelId = channelId,
            notificationManager = notificationManager
        )
    }

    // --- Envelope keys are exactly as documented ---

    @Test
    fun envelopeKeys_areTheCrossPlatformSpecKeys() {
        // Lock the wire-format constants so any future rename surfaces here.
        // Failure to update both the SDK and CIO backend would silently break live notifications.
        assert(LiveNotificationHandler.ACTIVITY_ID_KEY == "activity_id")
        assert(LiveNotificationHandler.EVENT_KEY == "event")
        assert(LiveNotificationHandler.ACTIVITY_TYPE_KEY == "activity_type")
        assert(LiveNotificationHandler.ATTRIBUTES_KEY == "attributes")
        assert(LiveNotificationHandler.CONTENT_STATE_KEY == "content_state")
    }

    // --- Happy-path dispatch ---

    @Test
    fun handle_givenAllFiveTemplates_postsNotificationForEach() {
        val templates = listOf(
            TemplateRegistry.DELIVERY_TRACKING,
            TemplateRegistry.FLIGHT_STATUS,
            TemplateRegistry.LIVE_SCORE,
            TemplateRegistry.COUNTDOWN_TIMER,
            TemplateRegistry.AUCTION_BID
        )
        for (activityType in templates) {
            val bundle = newBundle(
                activityType = activityType,
                attributesJson = "{}",
                contentStateJson = "{}"
            )
            invoke(handlerFor(bundle))
        }

        verify(exactly = templates.size) {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_postsNotificationKeyedByActivityIdHash() {
        val activityId = "live-activity-id-xyz"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(
            activityId = activityId,
            attributesJson = """{"orderId":"A-1","recipientName":"User"}""",
            contentStateJson = """{"statusMessage":"Out for delivery","stepCurrent":2,"stepTotal":4}"""
        )

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
    }

    // --- Missing required fields short-circuit ---

    @Test
    fun handle_givenMissingActivityId_returnsEarlyWithoutNotifying() {
        val bundle = newBundle(activityId = null)

        invoke(handlerFor(bundle))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenMissingActivityType_dropsAndDoesNotNotify() {
        val bundle = newBundle(activityType = null)

        invoke(handlerFor(bundle))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenUnknownActivityType_dropsAndDoesNotNotify() {
        val bundle = newBundle(activityType = "io.customer.live.bogus")

        invoke(handlerFor(bundle))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenBareTemplateNameWithoutSpecPrefix_dropsAndDoesNotNotify() {
        // The cross-platform spec requires the `io.customer.live.` prefix.
        // Bare names like "delivery_tracking" must be rejected to stay aligned with iOS.
        val bundle = newBundle(activityType = "delivery_tracking")

        invoke(handlerFor(bundle))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    // --- Malformed JSON tolerated ---

    @Test
    fun handle_givenMalformedAttributesJson_stillPostsNotification() {
        // Lenient parsing — malformed JSON in one slot becomes an empty JSONObject so
        // the template still attempts to render with defaults rather than dropping the push.
        val bundle = newBundle(
            attributesJson = "{this is not json",
            contentStateJson = "{}"
        )

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenMalformedContentStateJson_stillPostsNotification() {
        val bundle = newBundle(
            attributesJson = "{}",
            contentStateJson = "}not json{"
        )

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenNullAttributesAndContentState_stillPostsNotification() {
        val bundle = newBundle(
            attributesJson = null,
            contentStateJson = null
        )

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    // --- End event dismisses after the documented delay ---

    @Test
    fun handle_givenEventEnd_schedulesDelayedCancel() {
        val activityId = "ending-activity"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(
            activityId = activityId,
            event = "end"
        )

        invoke(handlerFor(bundle))

        // Notification was posted immediately and a cancel was queued on the main looper.
        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
        assertCalledNever {
            notificationManager.cancel(activityId, expectedNotifId)
        }

        // Drain the main looper to execute the postDelayed dismiss task.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).runToEndOfTasks()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        verify(exactly = 1) {
            notificationManager.cancel(activityId, expectedNotifId)
        }
    }

    @Test
    fun handle_givenEventStart_doesNotScheduleCancel() {
        val activityId = "starting-activity"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(activityId = activityId, event = "start")

        invoke(handlerFor(bundle))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
        assertCalledNever {
            notificationManager.cancel(activityId, expectedNotifId)
        }
    }
}
