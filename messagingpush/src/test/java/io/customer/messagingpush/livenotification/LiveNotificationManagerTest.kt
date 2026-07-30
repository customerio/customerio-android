package io.customer.messagingpush.livenotification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.messagingpush.data.communication.CustomerIOLiveNotificationsCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Tests for [LiveNotificationManager], the on-device (client-initiated) path.
 *
 * Local start/update are reported to Customer.io; push-delivered start/update
 * are backend-initiated and reported by neither the manager nor the handler
 * (see [io.customer.messagingpush.LiveNotificationHandler]). Reporting happens
 * after rendering regardless of whether the type is enabled, so these tests do
 * not need a module config attached.
 */
@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationManagerTest : IntegrationTest() {

    private val lifecycleClient: LiveNotificationLifecycleClient = mockk(relaxed = true)
    private val manager = LiveNotificationManager(lifecycleClient)

    private val type = "io.customer.livenotifications.segments"

    override fun setup(testConfig: TestConfig) {
        // Render runs on the background dispatcher; the stub makes it inline+synchronous.
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<DispatchersProvider>(DispatchersProviderStub())
                    }
                }
            } + testConfig
        )
    }

    private fun saveToken() = SDKComponent.android().globalPreferenceStore.saveDeviceToken("fcm-tok")

    @Test
    fun start_reportsStartEventWithAttributesAndContentState() {
        saveToken()

        manager.start(
            "act-1",
            type,
            attributes = mapOf("header" to "Order update"),
            contentState = mapOf("title" to "Preparing")
        )

        verify { lifecycleClient.reportStart("act-1", type, "fcm-tok", any(), any()) }
    }

    @Test
    fun update_reportsNoLifecycleEvent() {
        // A locally-triggered update re-renders the activity but must NOT emit a CDP
        // "Live Notification Event": only start/end are reported.
        saveToken()

        manager.update(
            "act-1",
            type,
            attributes = emptyMap(),
            contentState = mapOf("title" to "Arriving")
        )

        verify(exactly = 0) { lifecycleClient.reportStart(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { lifecycleClient.reportEnd(any(), any(), any()) }
    }

    @Test
    fun update_afterEnded_isIgnored() {
        // The activity ended locally or was dismissed (marked terminal): a later update
        // must not repost it. (Updates are never reported regardless; this covers the guard.)
        saveToken()
        SDKComponent.liveNotificationStore.markEnded("act-1")

        manager.update("act-1", type, attributes = emptyMap(), contentState = mapOf("title" to "Late"))

        verify(exactly = 0) { lifecycleClient.reportStart(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { lifecycleClient.reportEnd(any(), any(), any()) }
    }

    @Test
    fun end_reportsEndUsingStoredType() {
        saveToken()
        // The SDK records the type when it renders the activity; the host ends with just the id.
        SDKComponent.liveNotificationStore.setActivityType("act-1", type)

        manager.end("act-1")

        verify { lifecycleClient.reportEnd("act-1", type, "fcm-tok") }
    }

    @Test
    fun end_marksEndedAndRetainsTypeForLogoutCancel() {
        saveToken()
        val store = SDKComponent.liveNotificationStore
        store.setActivityType("act-1", type)

        manager.end("act-1")

        // End is terminal: the id is marked ended and its activity type is retained so a
        // later logout can still cancel an ended-but-still-visible notification.
        store.isEnded("act-1").shouldBeTrue()
        store.activityType("act-1") shouldBeEqualTo type
    }

    @Test
    fun end_calledTwice_reportsEndOnce() {
        saveToken()
        SDKComponent.liveNotificationStore.setActivityType("act-1", type)

        manager.end("act-1")
        manager.end("act-1")

        // The second end is a no-op (already terminal): end is reported at most once per id.
        verify(exactly = 1) { lifecycleClient.reportEnd("act-1", type, "fcm-tok") }
    }

    @Test
    fun end_afterLocalStart_reportsEndEvenWhenTypeNotPersisted() {
        // A local start remembers the type for its id, so end() reports a matching
        // end even if the render never persisted the activity type.
        saveToken()
        manager.start("act-1", type, attributes = emptyMap(), contentState = mapOf("title" to "Preparing"))
        SDKComponent.liveNotificationStore.clearActivityType("act-1")

        manager.end("act-1")

        verify { lifecycleClient.reportEnd("act-1", type, "fcm-tok") }
    }

    @Test
    fun end_unknownActivity_doesNotReport() {
        saveToken()
        SDKComponent.liveNotificationStore.clearActivityType("act-unknown")

        manager.end("act-unknown")

        verify(exactly = 0) { lifecycleClient.reportEnd(any(), any(), any()) }
    }

    @Test
    fun end_afterCancelAllActivities_reportsNothing() {
        // Logout must drop cached bundles too, so a later end() for a recycled id
        // can't resurrect a previous session's activity.
        saveToken()
        manager.start("act-1", type, attributes = emptyMap(), contentState = mapOf("title" to "Preparing"))

        manager.cancelAllActivities()
        manager.end("act-1")

        verify(exactly = 0) { lifecycleClient.reportEnd(any(), any(), any()) }
    }

    @Test
    fun cancelAllActivities_clearsTrackedStateWithoutReporting() {
        saveToken()
        val store = SDKComponent.liveNotificationStore
        store.setActivityType("act-1", type)
        store.setActivityType("act-2", type)

        manager.cancelAllActivities()

        store.trackedActivityIds().shouldBeEmpty()
        // Logout must NOT emit end events.
        verify(exactly = 0) { lifecycleClient.reportEnd(any(), any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun renderQueuedBeforeReset_isDroppedAndNotReAddedToStore() {
        // A render enqueued by start() before a logout must not run afterward and
        // re-add the previous user's activity into the just-cleared store.
        saveToken()
        // Defer the render (StandardTestDispatcher) so we can interleave the reset
        // between enqueue and execution deterministically.
        val scheduler = TestCoroutineScheduler()
        val deferred = StandardTestDispatcher(scheduler)
        SDKComponent.overrideDependency<DispatchersProvider>(
            object : DispatchersProvider {
                override val background: CoroutineDispatcher = deferred
                override val main: CoroutineDispatcher = deferred
                override val default: CoroutineDispatcher = deferred
            }
        )
        val manager = LiveNotificationManager(lifecycleClient)

        manager.start("act-1", type, attributes = emptyMap(), contentState = mapOf("title" to "Preparing"))
        manager.cancelAllActivities()
        scheduler.advanceUntilIdle()

        // The queued render saw the bumped generation and dropped, so nothing was re-added.
        SDKComponent.liveNotificationStore.trackedActivityIds().shouldBeEmpty()
    }

    @Test
    fun start_givenThrowingAppRenderer_doesNotCrashAndStillReports() {
        // The render runs on an SDK-owned coroutine scope with no exception handler, so an
        // app-supplied renderer that throws would otherwise take the host process down from a
        // thread the app cannot guard. The failure must be contained to this render.
        saveToken()
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder()
                .enableLiveNotificationTypes(LiveNotificationType.SEGMENTS)
                .setLiveNotificationCallback(
                    object : CustomerIOLiveNotificationsCallback {
                        override fun createLiveNotification(
                            payload: CustomerIOParsedPushPayload,
                            context: Context
                        ): Notification = throw IllegalStateException("app renderer blew up")
                    }
                )
                .build()
        ).attachToSDKComponent()

        // Would propagate out of the render coroutine and crash the process before the fix.
        manager.start("act-throw", type, attributes = emptyMap(), contentState = mapOf("status" to "Preparing"))

        // Reporting is decoupled from rendering, so the lifecycle event is still emitted.
        verify { lifecycleClient.reportStart("act-throw", type, "fcm-tok", any(), any()) }
    }

    @Test
    fun update_givenThrowingAppRenderer_laterRendersStillRun() {
        // The render chain must survive a failed render: a subsequent update still posts.
        saveToken()
        var shouldThrow = true
        val notificationManager = SDKComponent.android().applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ModuleMessagingPushFCM(
            MessagingPushModuleConfig.Builder()
                .enableLiveNotificationTypes(LiveNotificationType.SEGMENTS)
                .setLiveNotificationCallback(
                    object : CustomerIOLiveNotificationsCallback {
                        override fun createLiveNotification(
                            payload: CustomerIOParsedPushPayload,
                            context: Context
                        ): Notification? {
                            if (shouldThrow) throw IllegalStateException("app renderer blew up")
                            return null // fall back to the SDK template
                        }
                    }
                )
                .build()
        ).attachToSDKComponent()

        manager.start("act-chain", type, attributes = emptyMap(), contentState = mapOf("status" to "Preparing"))
        shouldThrow = false
        manager.update("act-chain", type, attributes = emptyMap(), contentState = mapOf("status" to "Arriving"))

        Shadows.shadowOf(notificationManager).allNotifications.shouldNotBeEmpty()
    }
}
