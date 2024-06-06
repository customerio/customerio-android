package io.customer.datapipelines

import io.customer.datapipelines.support.core.JUnitTest
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class DataPipelineEventBusInteractionTest : JUnitTest() {

    private lateinit var eventBus: EventBus

    override fun setupAndroidSDKComponent() {
        eventBus = mockk(relaxed = true)
        SDKComponent.overrideDependency(EventBus::class.java, eventBus)
        super.setupAndroidSDKComponent()
    }

    @Test
    fun givenCustomerIOInstanceCreated_expectSubscribersAddedToEventBus() {
        // Verify the subscribe method is called with the correct event classes
        verify(exactly = 1) { eventBus.subscribe<Event.TrackPushMetricEvent>(any()) }
        verify(exactly = 1) { eventBus.subscribe<Event.TrackInAppMetricEvent>(any()) }
        verify(exactly = 1) { eventBus.subscribe<Event.RegisterDeviceTokenEvent>(any()) }
    }
}
