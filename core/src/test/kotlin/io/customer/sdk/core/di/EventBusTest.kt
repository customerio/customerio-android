package io.customer.sdk.core.di

import io.customer.commontest.BaseUnitTest
import io.customer.commontest.util.ScopeProviderStub
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBusImpl
import io.customer.sdk.core.util.ScopeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldHaveSingleItem
import org.junit.Test

class EventBusTest : BaseUnitTest() {

    private lateinit var eventBus: EventBusImpl
    private var testScopeProvider = ScopeProviderStub()

    override fun setup() {
        SDKComponent.overrideDependency(ScopeProvider::class.java, testScopeProvider)
        eventBus = EventBusImpl()
    }

    override fun teardown() {
        eventBus.removeAllSubscriptions()
        super.teardown()
    }

    @Test
    fun givenPublishEventVerifySubscribe() = runBlocking {
        val events = mutableListOf<Event>()
        val job = eventBus.subscribe<Event.ProfileIdentifiedEvent> { event ->
            events.add(event)
        }

        val testEvent = Event.ProfileIdentifiedEvent("Test Message")
        println("Publishing event: $testEvent")
        eventBus.publish(testEvent)

        delay(100) // Give some time for the event to be collected

        events.shouldHaveSingleItem()
            .shouldBeInstanceOf<Event.ProfileIdentifiedEvent>()
            .identifier shouldBeEqualTo testEvent.identifier

        job.cancel()
    }

    @Test
    fun givenCancelAllShouldStopReceivingEvents(): Unit = runBlocking {
        val events = mutableListOf<Event>()
        eventBus.subscribe<Event.ScreenViewedEvent> { event ->
            events.add(event)
        }

        val firstEvent = Event.ScreenViewedEvent("First Message")
        println("Publishing first event: $firstEvent")
        eventBus.publish(firstEvent)

        delay(100) // Give some time for the event to be collected

        assertEquals(1, events.size)
        assertEquals(firstEvent.name, (events[0] as Event.ScreenViewedEvent).name)

        println("Cancelling all...")
        eventBus.removeAllSubscriptions()

        val secondEvent = Event.ScreenViewedEvent("Second Message")
        println("Publishing second event: $secondEvent")
        eventBus.publish(secondEvent)

        delay(100) // Give some time for the event to be collected

        events.size shouldBeEqualTo 1 // No new events should be collected after cancelAll()
    }

    @Test
    fun givenMultipleSubscribersExpectAllSubscribersReceiveEvents() = runBlocking {
        val subscriber1 = mutableListOf<Event>()
        val subscriber2 = mutableListOf<Event>()

        val job1 = eventBus.subscribe<Event.TrackPushMetricEvent> { event ->
            subscriber1.add(event)
        }

        val job2 = eventBus.subscribe<Event.TrackPushMetricEvent> { event ->
            subscriber2.add(event)
        }

        val testEvent = Event.TrackPushMetricEvent("Delivery ID", "Event", "Device Token")
        println("Publishing event: $testEvent")
        eventBus.publish(testEvent)

        delay(100) // Give some time for the event to be collected

        subscriber1.shouldHaveSingleItem()
            .shouldBeInstanceOf<Event.TrackPushMetricEvent>()
            .also {
                it.deliveryId shouldBeEqualTo testEvent.deliveryId
                it.event shouldBeEqualTo testEvent.event
                it.deviceToken shouldBeEqualTo testEvent.deviceToken
            }

        subscriber2.shouldHaveSingleItem()
            .shouldBeInstanceOf<Event.TrackPushMetricEvent>()
            .also {
                it.deliveryId shouldBeEqualTo testEvent.deliveryId
                it.event shouldBeEqualTo testEvent.event
                it.deviceToken shouldBeEqualTo testEvent.deviceToken
            }

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun givePublishMultipleEventsToMultipleSubscribersExpectAllEventsReceived() = runBlocking {
        val subscriber1 = mutableListOf<Event>()
        val subscriber2 = mutableListOf<Event>()

        val job1 = eventBus.subscribe<Event.RegisterDeviceTokenEvent> { event ->
            subscriber1.add(event)
        }

        val job2 = eventBus.subscribe<Event.RegisterDeviceTokenEvent> { event ->
            subscriber2.add(event)
        }

        val testEvent1 = Event.RegisterDeviceTokenEvent("Token 1")
        val testEvent2 = Event.RegisterDeviceTokenEvent("Token 2")
        println("Publishing events: $testEvent1, $testEvent2")
        eventBus.publish(testEvent1)
        eventBus.publish(testEvent2)

        delay(100) // Give some time for the events to be collected

        subscriber1.size shouldBeEqualTo 2
        subscriber1.map { it as Event.RegisterDeviceTokenEvent }.any { it.token == "Token 1" } shouldBe true
        subscriber1.map { it as Event.RegisterDeviceTokenEvent }.any { it.token == "Token 2" } shouldBe true

        subscriber2.size shouldBeEqualTo 2
        subscriber2.map { it as Event.RegisterDeviceTokenEvent }.any { it.token == "Token 1" } shouldBe true
        subscriber2.map { it as Event.RegisterDeviceTokenEvent }.any { it.token == "Token 2" } shouldBe true

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun givenSubscribeToEventTypeNeverPublishedExpectNoEvents() = runBlocking {
        val events = mutableListOf<Event>()
        val job = eventBus.subscribe<Event.TrackInAppMetricEvent> { event ->
            events.add(event)
        }

        val unrelatedEvent = Event.ResetEvent
        eventBus.publish(unrelatedEvent)

        delay(100) // Give some time to ensure no events are collected

        assertEquals(0, events.size) // No events should be collected

        job.cancel()
    }

    @Test
    fun givenBufferEventsAndReplayToNewSubscriberExpectAllEventsReceived() = runBlocking {
        // Publish multiple events without any subscribers
        repeat(15) { index ->
            val event = Event.TrackInAppMetricEvent("deliveryId$index", "event$index", params = mapOf("message" to "Message $index"))
            println("Publishing event: $event")
            eventBus.publish(event)
        }

        delay(100) // Give some time for the events to be published

        val events = mutableListOf<Event>()
        val job = eventBus.subscribe<Event.TrackInAppMetricEvent> { event ->
            events.add(event)
        }

        delay(100) // Give some time for the events to be collected by the new subscriber

        for (i in 0 until 15) {
            (events[i] as Event.TrackInAppMetricEvent).event shouldBeEqualTo "event$i"
            (events[i] as Event.TrackInAppMetricEvent).deliveryID shouldBeEqualTo "deliveryId$i"
            (events[i] as Event.TrackInAppMetricEvent).params shouldBeEqualTo mapOf("message" to "Message $i")
        }

        job.cancel()
    }
}
