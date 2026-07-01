package io.customer.messagingpush.livenotification

import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    private val type = "io.customer.liveactivities.deliverytracking"

    private fun saveToken() = SDKComponent.android().globalPreferenceStore.saveDeviceToken("fcm-tok")

    @Test
    fun start_reportsStartEvent() {
        saveToken()

        manager.start("act-1", type, mapOf("statusMessage" to "Preparing"))

        verify { lifecycleClient.reportStart("act-1", type, "fcm-tok", any()) }
    }

    @Test
    fun update_reportsUpdateEvent() {
        saveToken()

        manager.update("act-1", type, mapOf("statusMessage" to "Arriving"))

        verify { lifecycleClient.reportUpdate("act-1", type, "fcm-tok", any()) }
    }

    @Test
    fun update_withoutFcmToken_doesNotReport() {
        SDKComponent.android().globalPreferenceStore.removeDeviceToken()

        manager.update("act-1", type, mapOf("statusMessage" to "Arriving"))

        verify(exactly = 0) { lifecycleClient.reportUpdate(any(), any(), any(), any()) }
    }
}
