package io.customer.location.geofence

import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceBroadcastReceiverTest : RobolectricTest() {

    // 'mock' prefix to avoid shadowing SDKComponent.eventBus / AndroidSDKComponent
    // extension properties inside `sdk { ... }` / `android { ... }` override lambdas.
    private val mockEventBus: EventBus = mockk(relaxed = true)
    private val mockScheduler: GeofenceEventScheduler = mockk(relaxed = true)
    private val mockCooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)

    private lateinit var receiver: GeofenceBroadcastReceiver

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    sdk { overrideDependency<EventBus>(mockEventBus) }
                    android {
                        overrideDependency<GeofenceEventScheduler>(mockScheduler)
                        overrideDependency<GeofenceCooldownFilter>(mockCooldownFilter)
                    }
                }
            }
        )
        // Default: cooldown allows emission. Tests override this to test suppression.
        every { mockCooldownFilter.tryAcquire(any(), any()) } returns true
        receiver = GeofenceBroadcastReceiver()
    }

    @Test
    fun handleGeofencingEvent_givenNullEvent_expectNothingPublished() = runTest {
        receiver.handleGeofencingEvent(null)

        verify(exactly = 0) { mockEventBus.publish(any<Event.GeofenceTransitionEvent>()) }
        coVerify(exactly = 0) { mockScheduler.schedule(any(), any(), any(), any(), any()) }
    }

    @Test
    fun handleGeofencingEvent_givenHasError_expectNothingPublished() = runTest {
        val event = mockk<GeofencingEvent>(relaxed = true) {
            every { hasError() } returns true
            every { errorCode } returns 1000
        }

        receiver.handleGeofencingEvent(event)

        verify(exactly = 0) { mockEventBus.publish(any<Event.GeofenceTransitionEvent>()) }
        coVerify(exactly = 0) { mockScheduler.schedule(any(), any(), any(), any(), any()) }
    }

    @Test
    fun handleGeofencingEvent_givenNullTriggeringGeofences_expectNothingPublished() = runTest {
        val event = mockk<GeofencingEvent>(relaxed = true) {
            every { hasError() } returns false
            every { geofenceTransition } returns Geofence.GEOFENCE_TRANSITION_ENTER
            every { triggeringGeofences } returns null
        }

        receiver.handleGeofencingEvent(event)

        verify(exactly = 0) { mockEventBus.publish(any<Event.GeofenceTransitionEvent>()) }
        coVerify(exactly = 0) { mockScheduler.schedule(any(), any(), any(), any(), any()) }
    }

    @Test
    fun handleGeofencingEvent_givenNullTriggeringLocation_expectEventPublishedWithoutLatLng() = runTest {
        val event = buildGeofencingEvent(
            transition = Geofence.GEOFENCE_TRANSITION_ENTER,
            geofenceIds = listOf("biz-1"),
            location = null
        )
        val capturedEvent = slot<Event>()
        every { mockEventBus.publish(capture(capturedEvent)) } returns Unit

        receiver.handleGeofencingEvent(event)

        val published = capturedEvent.captured.shouldBeInstanceOf<Event.GeofenceTransitionEvent>()
        published.geofenceId shouldBeEqualTo "biz-1"
        published.properties["latitude"].shouldBeNull()
        published.properties["longitude"].shouldBeNull()
    }

    @Test
    fun handleGeofencingEvent_givenValidEnterEvent_expectEventPublishedWithLatLng() = runTest {
        val event = buildGeofencingEvent(
            transition = Geofence.GEOFENCE_TRANSITION_ENTER,
            geofenceIds = listOf("biz-1"),
            location = realLocation(37.7749, -122.4194)
        )
        val capturedEvent = slot<Event>()
        every { mockEventBus.publish(capture(capturedEvent)) } returns Unit

        receiver.handleGeofencingEvent(event)

        val published = capturedEvent.captured.shouldBeInstanceOf<Event.GeofenceTransitionEvent>()
        published.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
        published.properties["latitude"] shouldBeEqualTo 37.7749
        published.properties["longitude"] shouldBeEqualTo -122.4194
    }

    @Test
    fun dispatchTransition_givenEnterTransition_expectScheduledAndPublished() = runTest {
        val capturedEvent = slot<Event>()
        every { mockEventBus.publish(capture(capturedEvent)) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence-1"),
            latitude = 37.7749,
            longitude = -122.4194
        )

        coVerify(exactly = 1) {
            mockScheduler.schedule(
                geofenceId = "biz-geofence-1",
                transition = Event.GeofenceTransition.ENTER,
                latitude = 37.7749,
                longitude = -122.4194,
                timestamp = any()
            )
        }

        val published = capturedEvent.captured.shouldBeInstanceOf<Event.GeofenceTransitionEvent>()
        published.geofenceId shouldBeEqualTo "biz-geofence-1"
        published.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
        published.properties["geofence_id"] shouldBeEqualTo "biz-geofence-1"
        published.properties["transition_type"] shouldBeEqualTo "enter"
        published.properties["latitude"] shouldBeEqualTo 37.7749
        published.properties["longitude"] shouldBeEqualTo -122.4194
        published.properties["timestamp"].shouldNotBeNull()
    }

    @Test
    fun dispatchTransition_givenExitTransition_expectScheduledAndPublished() = runTest {
        val capturedEvent = slot<Event>()
        every { mockEventBus.publish(capture(capturedEvent)) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_EXIT,
            triggeringGeofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 1) {
            mockScheduler.schedule(
                geofenceId = "biz-geofence-2",
                transition = Event.GeofenceTransition.EXIT,
                latitude = 51.5074,
                longitude = -0.1278,
                timestamp = any()
            )
        }

        val published = capturedEvent.captured.shouldBeInstanceOf<Event.GeofenceTransitionEvent>()
        published.transition shouldBeEqualTo Event.GeofenceTransition.EXIT
        published.properties["transition_type"] shouldBeEqualTo "exit"
    }

    @Test
    fun dispatchTransition_givenMovementTriggerGeofence_expectNothingScheduledOrPublished() = runTest {
        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_EXIT,
            triggeringGeofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { mockEventBus.publish(any<Event.GeofenceTransitionEvent>()) }
    }

    @Test
    fun dispatchTransition_givenUnknownTransitionType_expectNothingScheduledOrPublished() = runTest {
        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_DWELL,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { mockEventBus.publish(any<Event.GeofenceTransitionEvent>()) }
    }

    @Test
    fun dispatchTransition_givenMissingLatLng_expectBothPathsReceiveNullLatLng() = runTest {
        val capturedEvent = slot<Event>()
        every { mockEventBus.publish(capture(capturedEvent)) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = null,
            longitude = null
        )

        coVerify(exactly = 1) {
            mockScheduler.schedule(
                geofenceId = "biz-geofence",
                transition = Event.GeofenceTransition.ENTER,
                latitude = null,
                longitude = null,
                timestamp = any()
            )
        }

        val published = capturedEvent.captured.shouldBeInstanceOf<Event.GeofenceTransitionEvent>()
        published.properties["latitude"].shouldBeNull()
        published.properties["longitude"].shouldBeNull()
        published.properties["geofence_id"] shouldBeEqualTo "biz-geofence"
    }

    @Test
    fun dispatchTransition_givenCooldownSuppresses_expectNothingScheduledOrPublished() = runTest {
        every { mockCooldownFilter.tryAcquire("biz-geofence", Event.GeofenceTransition.ENTER) } returns false

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { mockEventBus.publish(any<Event.GeofenceTransitionEvent>()) }
    }

    @Test
    fun dispatchTransition_givenCooldownAllows_expectTryAcquireBeforeScheduleAndPublish() = runTest {
        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerifyOrder {
            mockCooldownFilter.tryAcquire("biz-geofence", Event.GeofenceTransition.ENTER)
            mockScheduler.schedule(any(), any(), any(), any(), any())
            mockEventBus.publish(any<Event.GeofenceTransitionEvent>())
        }
    }

    @Test
    fun dispatchTransition_givenBatchWithMovementTriggerAndBusiness_expectOnlyBusinessPublished() = runTest {
        val capturedEvents = mutableListOf<Event>()
        every { mockEventBus.publish(capture(capturedEvents)) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        capturedEvents.size shouldBeEqualTo 1
        val published = capturedEvents.first().shouldBeInstanceOf<Event.GeofenceTransitionEvent>()
        published.geofenceId shouldBeEqualTo "biz-geofence"
    }

    @Test
    fun dispatchTransition_givenSchedulerThrows_expectEventBusStillPublished() = runTest {
        coEvery { mockScheduler.schedule(any(), any(), any(), any(), any()) } throws RuntimeException("WM internal")
        val capturedEvent = slot<Event>()
        every { mockEventBus.publish(capture(capturedEvent)) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        val published = capturedEvent.captured.shouldBeInstanceOf<Event.GeofenceTransitionEvent>()
        published.geofenceId shouldBeEqualTo "biz-geofence-1"
    }

    @Test
    fun dispatchTransition_givenSchedulerThrowsOnFirstGeofence_expectSecondGeofenceStillProcessed() = runTest {
        coEvery {
            mockScheduler.schedule("biz-1", any(), any(), any(), any())
        } throws RuntimeException("WM internal")
        coEvery {
            mockScheduler.schedule("biz-2", any(), any(), any(), any())
        } returns Unit
        val capturedEvents = mutableListOf<Event>()
        every { mockEventBus.publish(capture(capturedEvents)) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-1", "biz-2"),
            latitude = 0.0,
            longitude = 0.0
        )

        capturedEvents.size shouldBeEqualTo 2
        capturedEvents.map { (it as Event.GeofenceTransitionEvent).geofenceId } shouldBeEqualTo listOf("biz-1", "biz-2")
    }

    private fun buildGeofencingEvent(
        transition: Int,
        geofenceIds: List<String>,
        location: Location?
    ): GeofencingEvent {
        val geofences = geofenceIds.map { id ->
            mockk<Geofence>(relaxed = true) { every { requestId } returns id }
        }
        return mockk(relaxed = true) {
            every { hasError() } returns false
            every { geofenceTransition } returns transition
            every { triggeringGeofences } returns geofences
            every { triggeringLocation } returns location
        }
    }

    private fun realLocation(lat: Double, lng: Double): Location =
        Location("test-provider").apply {
            latitude = lat
            longitude = lng
            time = System.currentTimeMillis()
        }
}
