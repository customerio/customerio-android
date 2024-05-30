package io.customer.sdk.core.di

import io.customer.commontest.BaseUnitTest
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBusImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class EventBusTest : BaseUnitTest() {

    private lateinit var eventBus: EventBusImpl
    private var testScope: TestScope = TestScope()

    override fun setup() {
        eventBus = EventBusImpl(scope = testScope)
    }

    override fun teardown() {
        eventBus.cancelAll()
        super.teardown()
    }

    @Test
    fun givenPublishEventVerifySubscribe() = testScope.runTest {
        val events = mutableListOf<Event.DummyEvent>()
        val job = eventBus.subscribe<Event.DummyEvent> { event ->
            events.add(event)
        }

        val testEvent = Event.DummyEvent("Test Message")
        println("Publishing event: $testEvent")
        eventBus.publish(testEvent)

        delay(100) // Give some time for the event to be collected

        events.size shouldBeEqualTo 1
        events[0].message shouldBeEqualTo testEvent.message

        job.cancel()
    }

    @Test
    fun givenCancelAllShouldStopReceivingEvents() = testScope.runTest {
        val events = mutableListOf<Event.DummyEvent>()
        eventBus.subscribe<Event.DummyEvent> { event ->
            events.add(event)
        }

        val firstEvent = Event.DummyEvent("First Message")
        println("Publishing first event: $firstEvent")
        eventBus.publish(firstEvent)

        delay(100) // Give some time for the event to be collected

        assertEquals(1, events.size)
        assertEquals(firstEvent.message, events[0].message) // Verify the message

        println("Cancelling all...")
        eventBus.cancelAll()

        val secondEvent = Event.DummyEvent("Second Message")
        println("Publishing second event: $secondEvent")
        eventBus.publish(secondEvent)

        delay(100) // Give some time for the event to be collected

        events.size shouldBeEqualTo 1 // No new events should be collected after cancelAll()
    }

    @Test
    fun givenMultipleSubscribersShouldReceiveMultipleEvents() = testScope.runTest {
        val events1 = mutableListOf<Event.DummyEvent>()
        val events2 = mutableListOf<Event.DummyEvent>()

        val job1 = eventBus.subscribe<Event.DummyEvent> { event ->
            events1.add(event)
        }

        val job2 = eventBus.subscribe<Event.DummyEvent> { event ->
            events2.add(event)
        }

        val testEvent = Event.DummyEvent("Test Message")
        println("Publishing event: $testEvent")
        eventBus.publish(testEvent)

        delay(100) // Give some time for the event to be collected

        events1.size shouldBeEqualTo 1
        events1[0].message shouldBeEqualTo testEvent.message // Verify the message

        events2.size shouldBeEqualTo 1
        events2[0].message shouldBeEqualTo testEvent.message // Verify the message

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `publish multiple events to multiple subscribers`() = testScope.runTest {
        val subscriber1 = mutableListOf<Event.DummyEvent>()
        val subscriber2 = mutableListOf<Event.DummyEvent>()

        val job1 = eventBus.subscribe<Event.DummyEvent> { event ->
            subscriber1.add(event)
        }

        val job2 = eventBus.subscribe<Event.DummyEvent> { event ->
            subscriber2.add(event)
        }

        val testEvent1 = Event.DummyEvent("Message 1")
        val testEvent2 = Event.DummyEvent("Message 2")
        println("Publishing events: $testEvent1, $testEvent2")
        eventBus.publish(testEvent1)
        eventBus.publish(testEvent2)

        delay(100) // Give some time for the events to be collected

        subscriber1.size shouldBeEqualTo 2
        subscriber1.any { it.message == "Message 1" } shouldBe true
        subscriber1.any { it.message == "Message 2" } shouldBe true

        subscriber2.size shouldBeEqualTo 2
        subscriber2.any { it.message == "Message 1" } shouldBe true
        subscriber2.any { it.message == "Message 2" } shouldBe true

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun givenSubscribeToEventTypeNeverPublishedExpectNoEvents() = testScope.runTest {
        val events = mutableListOf<Event>()
        val job = eventBus.subscribe<Event.DummyEvent> { event ->
            events.add(event)
        }

        val unrelatedEvent = Event.DummyEmptyEvent()
        eventBus.publish(unrelatedEvent)

        delay(100) // Give some time to ensure no events are collected

        assertEquals(0, events.size) // No events should be collected

        job.cancel()
    }
}
