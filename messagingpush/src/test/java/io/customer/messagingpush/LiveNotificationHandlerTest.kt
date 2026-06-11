package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import io.customer.commontest.extensions.assertCalledNever
import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * Tests for [LiveNotificationHandler] focused on envelope parsing and dispatch:
 *
 * - top-level wire keys (`activity_id`, `event`, `activity_type`, `timestamp`,
 *   `dismissal_date`) are read from the [Bundle];
 * - template fields arrive flattened at the envelope top level (no
 *   `attributes` / `content_state` split);
 * - missing `activity_id`, `event`, or unknown `activity_type` are dropped
 *   without posting a notification;
 * - `event = "end"` cancels the notification immediately.
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
        data: JSONObject = JSONObject(),
        timestamp: Long? = null,
        dismissalDate: Long? = null
    ): Bundle {
        val bundle = Bundle()
        if (activityId != null) bundle.putString(LiveNotificationHandler.ACTIVITY_ID_KEY, activityId)
        if (event != null) bundle.putString(LiveNotificationHandler.EVENT_KEY, event)
        if (activityType != null) bundle.putString(LiveNotificationHandler.ACTIVITY_TYPE_KEY, activityType)
        if (timestamp != null) bundle.putString(LiveNotificationHandler.TIMESTAMP_KEY, timestamp.toString())
        if (dismissalDate != null) bundle.putString(LiveNotificationHandler.DISMISSAL_DATE_KEY, dismissalDate.toString())
        // Template fields ride flattened at the top level, as the backend delivers them.
        for (key in data.keys()) bundle.putString(key, data.get(key).toString())
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
        LiveNotificationHandler.ACTIVITY_ID_KEY shouldBeEqualTo "activity_id"
        LiveNotificationHandler.EVENT_KEY shouldBeEqualTo "event"
        LiveNotificationHandler.ACTIVITY_TYPE_KEY shouldBeEqualTo "activity_type"
        LiveNotificationHandler.TIMESTAMP_KEY shouldBeEqualTo "timestamp"
        LiveNotificationHandler.DISMISSAL_DATE_KEY shouldBeEqualTo "dismissal_date"
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
            put("orderId", "A-1")
            put("recipientName", "User")
            put("statusMessage", "Out for delivery")
            put("stepCurrent", 2)
            put("stepTotal", 4)
        }
        val bundle = newBundle(activityId = activityId, data = data)

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
            put("targetDate", System.currentTimeMillis() + 60_000L)
            put("statusMessage", "Sale starts in")
        }
        invoke(handlerFor(newBundle(activityType = TemplateRegistry.COUNTDOWN_TIMER, data = data)))

        posted.captured.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() shouldBeEqualTo "Flash Sale"
    }

    @Test
    fun handle_givenNestedJsonFieldAsString_parsesAndPosts() {
        // Nested objects (origin, homeTeam, …) arrive as JSON strings in FCM data;
        // the handler parses them so templates can read the nested values.
        val data = JSONObject().apply {
            put("flightNumber", "AA1")
            put("origin", JSONObject().put("code", "JFK"))
            put("destination", JSONObject().put("code", "LAX"))
            put("statusMessage", "On time")
        }
        val bundle = newBundle(activityType = TemplateRegistry.FLIGHT_STATUS, data = data)

        invoke(handlerFor(bundle))

        verify(exactly = 1) {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
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
    fun handle_givenMissingActivityType_dropsAndDoesNotNotify() {
        invoke(handlerFor(newBundle(activityType = null)))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenUnknownActivityType_dropsAndDoesNotNotify() {
        invoke(handlerFor(newBundle(activityType = "io.customer.liveactivities.bogus")))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    @Test
    fun handle_givenBareTemplateNameWithoutSpecPrefix_dropsAndDoesNotNotify() {
        // The cross-platform spec requires the `io.customer.liveactivities.` prefix.
        // Bare names like "deliverytracking" must be rejected to stay aligned with iOS.
        invoke(handlerFor(newBundle(activityType = "deliverytracking")))

        assertCalledNever {
            notificationManager.notify(any<String>(), any<Int>(), any<Notification>())
        }
    }

    // --- End event dismisses immediately ---

    @Test
    fun handle_givenEventEnd_cancelsImmediately() {
        val activityId = "ending-activity"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(activityId = activityId, event = "end")

        invoke(handlerFor(bundle))

        // Final state is posted, then removed immediately (dismissal_date scheduling
        // arrives with the lifecycle-reporting work).
        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
        verify(exactly = 1) {
            notificationManager.cancel(activityId, expectedNotifId)
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
    fun handle_givenNewerTimestamp_rendersBoth() {
        val activityId = "in-order-activity"

        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 100L)))
        invoke(handlerFor(newBundle(activityId = activityId, event = "update", timestamp = 200L)))

        verify(exactly = 2) {
            notificationManager.notify(activityId, any<Int>(), any<Notification>())
        }
    }

    // --- dismissal_date scheduling on end ---

    @Test
    fun handle_givenEndWithFutureDismissalDate_cancelsOnlyAfterDelay() {
        val activityId = "scheduled-end"
        val expectedNotifId = activityId.hashCode() and 0x7FFFFFFF
        val bundle = newBundle(
            activityId = activityId,
            event = "end",
            dismissalDate = System.currentTimeMillis() + 60_000L
        )

        invoke(handlerFor(bundle))

        // Posted now, but not cancelled until the dismissal_date is reached.
        verify(exactly = 1) {
            notificationManager.notify(activityId, expectedNotifId, any<Notification>())
        }
        assertCalledNever {
            notificationManager.cancel(activityId, expectedNotifId)
        }

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        verify(exactly = 1) {
            notificationManager.cancel(activityId, expectedNotifId)
        }
    }
}
