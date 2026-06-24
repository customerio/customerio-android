package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class DataPipelineEventBusInteractionTest : JUnitTest() {
    private lateinit var eventBus: EventBus

    // 'mock' prefix to avoid shadowing the real androidSDKComponent.secureUserStore.
    private val mockSecureUserStore: SecureUserStore = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    sdk { overrideDependency<EventBus>(mockk(relaxed = true)) }
                    android { overrideDependency<SecureUserStore>(mockSecureUserStore) }
                }
            }
        )

        eventBus = SDKComponent.eventBus
    }

    @Test
    fun givenCustomerIOInstanceCreated_expectSubscribersAddedToEventBus() {
        every { eventBus.subscribe(Event.TrackPushMetricEvent::class, any()) } returns mockk()
        every { eventBus.subscribe(Event.TrackInAppMetricEvent::class, any()) } returns mockk()
        every { eventBus.subscribe(Event.RegisterDeviceTokenEvent::class, any()) } returns mockk()

        // Verify the subscribe method is called with the correct event classes
        assertCalledOnce { eventBus.subscribe(Event.TrackPushMetricEvent::class, any()) }
        assertCalledOnce { eventBus.subscribe(Event.TrackInAppMetricEvent::class, any()) }
        assertCalledOnce { eventBus.subscribe(Event.RegisterDeviceTokenEvent::class, any()) }
    }

    @Test
    fun givenIdentify_expectSecureUserStoreWrittenSynchronously() {
        // identify() must persist the userId to secureUserStore synchronously (before the
        // UserChangedEvent is delivered) so subscribers gating on it (geofence sync) and
        // direct-API consumers see the new identity immediately. EventBus is mocked, so this
        // passes only if the write happens in identify() itself, not via event dispatch.
        sdkInstance.identify("user-sync")

        verify(exactly = 1) { mockSecureUserStore.saveUserId("user-sync") }
    }
}
