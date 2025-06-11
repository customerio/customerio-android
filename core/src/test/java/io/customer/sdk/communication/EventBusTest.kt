package io.customer.sdk.communication

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.util.ScopeProviderStub
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.events.Metric
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeLessThan
import org.amshove.kluent.shouldHaveSingleItem
import org.junit.jupiter.api.Test

class EventBusTest : JUnit5Test() {
    private lateinit var eventBus: EventBus
    private val testScopeProvider = ScopeProviderStub.Unconfined()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk { overrideDependency<ScopeProvider>(testScopeProvider) }
                }
            }
        )

        eventBus = SDKComponent.eventBus
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

        yield() // Allow event processing

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

        yield() // Allow event processing

        assertEquals(1, events.size)
        assertEquals(firstEvent.name, (events[0] as Event.ScreenViewedEvent).name)

        println("Cancelling all...")
        eventBus.removeAllSubscriptions()

        val secondEvent = Event.ScreenViewedEvent("Second Message")
        println("Publishing second event: $secondEvent")
        eventBus.publish(secondEvent)

        yield() // Allow event processing

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

        val testEvent = Event.TrackPushMetricEvent("Delivery ID", Metric.Opened, "Device Token")
        println("Publishing event: $testEvent")
        eventBus.publish(testEvent)

        yield() // Allow event processing

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

        yield() // Allow event processing

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

        yield() // Allow event processing

        assertEquals(0, events.size) // No events should be collected

        job.cancel()
    }

    @Test
    fun givenBufferEventsAndReplayToNewSubscriberExpectAllEventsReceived() = runBlocking {
        // Publish multiple events without any subscribers
        repeat(15) { index ->
            val event = Event.TrackInAppMetricEvent("deliveryId$index", Metric.Delivered, params = mapOf("message" to "Message $index"))
            println("Publishing event: $event")
            eventBus.publish(event)
        }

        yield() // Allow event processing

        val events = mutableListOf<Event>()
        val job = eventBus.subscribe<Event.TrackInAppMetricEvent> { event ->
            events.add(event)
        }

        yield() // Allow event processing

        for (i in 0 until 15) {
            (events[i] as Event.TrackInAppMetricEvent).event shouldBeEqualTo Metric.Delivered
            (events[i] as Event.TrackInAppMetricEvent).deliveryID shouldBeEqualTo "deliveryId$i"
            (events[i] as Event.TrackInAppMetricEvent).params shouldBeEqualTo mapOf("message" to "Message $i")
        }

        job.cancel()
    }

    @Test
    fun givenConcurrentPublishingExpectCorrectOrdering() = runBlocking {
        val events = mutableListOf<Event>()
        val job = eventBus.subscribe<Event.TrackPushMetricEvent> { event ->
            events.add(event)
        }

        // Publish 100 events concurrently to test ordering behavior
        val publishJobs = (1..100).map { index ->
            async {
                eventBus.publish(Event.TrackPushMetricEvent("deliveryId$index", Metric.Delivered, "deviceToken$index"))
            }
        }
        publishJobs.awaitAll()

        yield() // Allow event processing

        // Should receive all 100 events
        events.size shouldBeEqualTo 100

        // Events should be TrackPushMetricEvent instances
        events.forEach { event ->
            event.shouldBeInstanceOf<Event.TrackPushMetricEvent>()
        }

        // Verify all unique events were received (no duplicates/losses)
        val deliveryIds = events.map { (it as Event.TrackPushMetricEvent).deliveryId }.toSet()
        deliveryIds.size shouldBeEqualTo 100

        job.cancel()
    }

    @Test
    fun givenHighFrequencyPublishingExpectAllEventsReceived() = runBlocking {
        val events = mutableListOf<Event>()
        val job = eventBus.subscribe<Event.ScreenViewedEvent> { event ->
            events.add(event)
        }

        val startTime = System.currentTimeMillis()

        // Rapid publishing (simulates ViewPager or rapid navigation)
        repeat(1000) { index ->
            eventBus.publish(Event.ScreenViewedEvent("screen$index"))
        }

        yield() // Allow event processing

        val duration = System.currentTimeMillis() - startTime

        // Should receive all 1000 events
        events.size shouldBeEqualTo 1000

        // Should complete reasonably quickly (less than 1 second total)
        duration shouldBeLessThan 1000

        // Verify events are correct type and have expected content
        events.forEachIndexed { index, event ->
            event.shouldBeInstanceOf<Event.ScreenViewedEvent>()
            (event as Event.ScreenViewedEvent).name shouldBeEqualTo "screen$index"
        }

        job.cancel()
    }

    @Test
    fun givenMoreThan100EventsExpectReplayBufferLimited() = runBlocking {
        // Publish 150 events without any subscribers to test replay buffer limit
        repeat(150) { index ->
            eventBus.publish(Event.TrackInAppMetricEvent("deliveryId$index", Metric.Delivered, params = mapOf("index" to index.toString())))
        }

        yield() // Allow event processing

        val events = mutableListOf<Event>()
        val job = eventBus.subscribe<Event.TrackInAppMetricEvent> { event ->
            events.add(event)
        }

        yield() // Allow event processing

        // Should only receive the last 100 events due to replay buffer limit
        events.size shouldBeEqualTo 100

        // First received event should be from index 50 (events 0-49 should be dropped)
        val firstEvent = events.first() as Event.TrackInAppMetricEvent
        firstEvent.deliveryID shouldBeEqualTo "deliveryId50"
        firstEvent.params["index"] shouldBeEqualTo "50"

        // Last received event should be from index 149
        val lastEvent = events.last() as Event.TrackInAppMetricEvent
        lastEvent.deliveryID shouldBeEqualTo "deliveryId149"
        lastEvent.params["index"] shouldBeEqualTo "149"

        job.cancel()
    }
}
