package io.customer.messagingpush.livenotification

import android.content.Intent
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationDismissReceiverTest : IntegrationTest() {

    private val type = "io.customer.livenotifications.segments"
    private val lifecycleClient: LiveNotificationLifecycleClient = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        // The end report runs on the background dispatcher; the stub makes it inline+synchronous.
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<DispatchersProvider>(DispatchersProviderStub())
                        overrideDependency<LiveNotificationLifecycleClient>(lifecycleClient)
                    }
                }
            } + testConfig
        )
    }

    private fun dismiss(activityId: String, activityType: String? = type) {
        val intent = Intent().apply {
            putExtra(LiveNotificationDismissReceiver.EXTRA_ACTIVITY_ID, activityId)
            activityType?.let { putExtra(LiveNotificationDismissReceiver.EXTRA_ACTIVITY_TYPE, it) }
        }
        LiveNotificationDismissReceiver().onReceive(contextMock, intent)
    }

    @Test
    fun onReceive_givenTokenAndUnendedActivity_reportsEndAndMarksTerminal() {
        // The core swipe-to-dismiss contract: a user clearing an in-progress live notification
        // reports `end` to Customer.io and marks the id terminal so a later push can't repost it.
        SDKComponent.android().globalPreferenceStore.saveDeviceToken("fcm-tok")

        dismiss("act-1")

        verify(exactly = 1) {
            lifecycleClient.reportEnd(
                instanceUUID = "act-1",
                activityType = type,
                deviceId = "fcm-tok",
                contentState = any()
            )
        }
        SDKComponent.liveNotificationStore.isEnded("act-1").shouldBeTrue()
    }

    @Test
    fun onReceive_givenAlreadyEndedActivity_doesNotReportSecondEnd() {
        // endLiveNotification (or a remote end) already claimed the terminal transition, so a
        // subsequent swipe must not emit a duplicate end.
        SDKComponent.android().globalPreferenceStore.saveDeviceToken("fcm-tok")
        SDKComponent.liveNotificationStore.markEnded("act-1")

        dismiss("act-1")

        verify(exactly = 0) { lifecycleClient.reportEnd(any(), any(), any(), any()) }
    }

    @Test
    fun onReceive_givenMissingActivityType_isIgnored() {
        SDKComponent.android().globalPreferenceStore.saveDeviceToken("fcm-tok")

        dismiss("act-1", activityType = null)

        verify(exactly = 0) { lifecycleClient.reportEnd(any(), any(), any(), any()) }
        SDKComponent.liveNotificationStore.isEnded("act-1").shouldBeFalse()
    }

    @Test
    fun onReceive_withoutFcmToken_stillMarksEndedSoNothingRepostsIt() {
        // The end isn't reportable without a token, and lifecycle events are never retried — but
        // the user did dismiss the notification, so the activity must still go terminal. Renders
        // consult that marker; leaving it unset let a queued local update or a later push repost
        // a notification the user had already cleared.
        SDKComponent.android().globalPreferenceStore.removeDeviceToken()

        dismiss("act-1")

        verify(exactly = 0) { lifecycleClient.reportEnd(any(), any(), any(), any()) }
        SDKComponent.liveNotificationStore.isEnded("act-1").shouldBeTrue()
    }
}
