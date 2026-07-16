package io.customer.messagingpush.livenotification

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeNull
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
    fun update_reportsUpdateEventWithContentState() {
        saveToken()

        manager.update(
            "act-1",
            type,
            attributes = emptyMap(),
            contentState = mapOf("title" to "Arriving")
        )

        verify { lifecycleClient.reportUpdate("act-1", type, "fcm-tok", any()) }
    }

    @Test
    fun update_withoutFcmToken_doesNotReport() {
        SDKComponent.android().globalPreferenceStore.removeDeviceToken()

        manager.update(
            "act-1",
            type,
            attributes = emptyMap(),
            contentState = mapOf("title" to "Arriving")
        )

        verify(exactly = 0) { lifecycleClient.reportUpdate(any(), any(), any(), any()) }
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
    fun end_clearsStoredActivityType() {
        saveToken()
        val store = SDKComponent.liveNotificationStore
        store.setActivityType("act-1", type)

        manager.end("act-1")

        store.activityType("act-1").shouldBeNull()
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
}
