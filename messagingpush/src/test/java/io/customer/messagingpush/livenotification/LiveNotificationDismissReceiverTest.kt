package io.customer.messagingpush.livenotification

import android.content.Intent
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import org.amshove.kluent.shouldBeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class LiveNotificationDismissReceiverTest : IntegrationTest() {

    private val type = "io.customer.livenotifications.segments"

    @Test
    fun onReceive_withoutFcmToken_doesNotMarkEnded() {
        // With no device token the receiver can't report `end`, so it must NOT mark the
        // id terminal — doing so would lose the end and block any later one.
        SDKComponent.android().globalPreferenceStore.removeDeviceToken()
        val intent = Intent().apply {
            putExtra(LiveNotificationDismissReceiver.EXTRA_ACTIVITY_ID, "act-1")
            putExtra(LiveNotificationDismissReceiver.EXTRA_ACTIVITY_TYPE, type)
        }

        LiveNotificationDismissReceiver().onReceive(contextMock, intent)

        SDKComponent.liveNotificationStore.isEnded("act-1").shouldBeFalse()
    }
}
