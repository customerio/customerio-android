package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.overrideDependency
import io.customer.commontest.extensions.verifyOnce
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DataPipelineEventBusInteractionTest : JUnitTest() {
    private lateinit var eventBus: EventBus

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                diGraph {
                    sdk { overrideDependency<EventBus>(mockk(relaxed = true)) }
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
        verifyOnce { eventBus.subscribe(Event.TrackPushMetricEvent::class, any()) }
        verifyOnce { eventBus.subscribe(Event.TrackInAppMetricEvent::class, any()) }
        verifyOnce { eventBus.subscribe(Event.RegisterDeviceTokenEvent::class, any()) }
    }
}
