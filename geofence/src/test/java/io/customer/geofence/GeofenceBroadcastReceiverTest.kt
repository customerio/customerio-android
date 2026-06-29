package io.customer.geofence

import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeBlank
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
    private val mockServices: GeofenceServices = mockk(relaxed = true)
    private val mockCooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val mockStore: GeofenceRegionStore = mockk(relaxed = true)
    private val mockManager: GeofenceManager = mockk(relaxed = true)
    private val mockSecureUserStore: SecureUserStore = mockk(relaxed = true)

    // Real disk-backed store (Robolectric filesDir). The mocked scheduler never
    // claims, so an appended entry stays in the store and we can assert on it.
    private val pendingStore get() = SDKComponent.android().pendingGeofenceDeliveryStore

    private lateinit var receiver: GeofenceBroadcastReceiver

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    sdk { overrideDependency<EventBus>(mockEventBus) }
                    android {
                        overrideDependency<GeofenceEventScheduler>(mockScheduler)
                        overrideDependency<GeofenceServices>(mockServices)
                        overrideDependency<GeofenceCooldownFilter>(mockCooldownFilter)
                        overrideDependency<GeofenceRegionStore>(mockStore)
                        overrideDependency<GeofenceManager>(mockManager)
                        overrideDependency<SecureUserStore>(mockSecureUserStore)
                    }
                }
            }
        )
        // Default: cooldown allows emission. Tests override this to test suppression.
        every { mockCooldownFilter.tryAcquire(any(), any()) } returns true
        // Default: an identified user is the common case; the snapshot lands on the entry.
        // Tests that need an anonymous-at-queue-time scenario override this to null.
        every { mockSecureUserStore.getUserId() } returns "user-42"
        // Default: all geofence IDs the tests reference are "registered" so the
        // dispatchTransition store filter is a no-op. Tests for the filter override.
        every { mockStore.getRegisteredIds() } returns setOf(
            GeofenceConstants.MOVEMENT_TRIGGER_ID,
            "biz-1",
            "biz-2",
            "biz-geofence",
            "biz-geofence-1",
            "biz-geofence-2"
        )
        pendingStore.removeAll()
        receiver = GeofenceBroadcastReceiver()
    }

    @Test
    fun handleGeofencingEvent_givenNullEvent_expectNothingScheduled() = runTest {
        receiver.handleGeofencingEvent(null)

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun handleGeofencingEvent_givenHasError_expectNothingScheduled() = runTest {
        val event = mockk<GeofencingEvent>(relaxed = true) {
            every { hasError() } returns true
            every { errorCode } returns 1000
        }

        receiver.handleGeofencingEvent(event)

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun handleGeofencingEvent_givenNullTriggeringGeofences_expectNothingScheduled() = runTest {
        val event = mockk<GeofencingEvent>(relaxed = true) {
            every { hasError() } returns false
            every { geofenceTransition } returns Geofence.GEOFENCE_TRANSITION_ENTER
            every { triggeringGeofences } returns null
        }

        receiver.handleGeofencingEvent(event)

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun handleGeofencingEvent_givenNullTriggeringLocation_expectEntryStillQueued() = runTest {
        // Business-geofence delivery doesn't depend on a triggering location, so
        // a null location must still queue the transition.
        val event = buildGeofencingEvent(
            transition = Geofence.GEOFENCE_TRANSITION_ENTER,
            geofenceIds = listOf("biz-1"),
            location = null
        )

        receiver.handleGeofencingEvent(event)

        val entry = pendingStore.loadAll().single()
        entry.geofenceId shouldBeEqualTo "biz-1"
    }

    @Test
    fun handleGeofencingEvent_givenValidEnterEvent_expectEntryQueued() = runTest {
        val event = buildGeofencingEvent(
            transition = Geofence.GEOFENCE_TRANSITION_ENTER,
            geofenceIds = listOf("biz-1"),
            location = realLocation(37.7749, -122.4194)
        )

        receiver.handleGeofencingEvent(event)

        val entry = pendingStore.loadAll().single()
        entry.geofenceId shouldBeEqualTo "biz-1"
        entry.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
    }

    @Test
    fun dispatchTransition_givenEnterTransition_expectEntryAppendedAndScheduledButNotPublished() = runTest {
        val entrySlot = slot<PendingGeofenceDelivery>()

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence-1"),
            latitude = 37.7749,
            longitude = -122.4194
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        val scheduled = entrySlot.captured
        scheduled.geofenceId shouldBeEqualTo "biz-geofence-1"
        scheduled.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
        scheduled.toEventProperties()["transition"] shouldBeEqualTo "enter"
        scheduled.timestamp.shouldNotBeNull()
        // A unique id is minted at capture and carried in properties.
        scheduled.transitionId.shouldNotBeBlank()
        scheduled.toEventProperties()["transitionId"] shouldBeEqualTo scheduled.transitionId

        // Durably recorded for the foreground flush, and not published inline:
        // exactly-once is now arbitrated by the store, not a double-send.
        pendingStore.loadAll() shouldBeEqualTo listOf(scheduled)
        verify(exactly = 0) { mockEventBus.publish(any<Event.GeofenceTransitionEvent>()) }
    }

    @Test
    fun dispatchTransition_givenIdentifiedUser_expectUserIdSnapshottedOnEntry() = runTest {
        // Snapshotting at queue time is what protects A's events from being
        // reattributed to B if A signs out + B signs in before delivery.
        every { mockSecureUserStore.getUserId() } returns "user-A"
        val entrySlot = slot<PendingGeofenceDelivery>()

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.userId shouldBeEqualTo "user-A"
    }

    @Test
    fun dispatchTransition_givenAnonymousSession_expectEntryQueuedAndNoWorkerScheduled() = runTest {
        // Worker can't HTTP without a userId, so for anonymous entries we skip
        // WorkManager entirely — the foreground flush is the only delivery path.
        every { mockSecureUserStore.getUserId() } returns null

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        pendingStore.loadAll().single().userId.shouldBeNull()
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun dispatchTransition_givenEmptyUserId_expectTreatedAsAnonymous() = runTest {
        // Matches the SDK's `isUserIdentified` semantics — empty = not identified.
        every { mockSecureUserStore.getUserId() } returns ""

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        pendingStore.loadAll().single().userId.shouldBeNull()
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun dispatchTransition_givenExitTransition_expectEntryScheduled() = runTest {
        val entrySlot = slot<PendingGeofenceDelivery>()

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_EXIT,
            triggeringGeofenceIds = listOf("biz-geofence-2"),
            latitude = 51.5074,
            longitude = -0.1278
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.transition shouldBeEqualTo Event.GeofenceTransition.EXIT
        entrySlot.captured.toEventProperties()["transition"] shouldBeEqualTo "exit"
    }

    @Test
    fun dispatchTransition_givenMovementTriggerExit_expectServicesNotifiedAndNothingScheduled() = runTest {
        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_EXIT,
            triggeringGeofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
            latitude = 37.7749,
            longitude = -122.4194
        )

        verify { mockServices.onMovementTriggerExit(37.7749, -122.4194) }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun dispatchTransition_givenMovementTriggerNonExit_expectServicesNotNotified() = runTest {
        // Movement trigger fires ENTER expectedly (INITIAL_TRIGGER_ENTER on re-registration)
        // and may also fire DWELL/ENTER on boot. Only EXIT drives a refresh — verify the
        // receiver ignores non-EXIT cases.
        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
            latitude = 0.0,
            longitude = 0.0
        )

        verify(exactly = 0) { mockServices.onMovementTriggerExit(any(), any()) }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun dispatchTransition_givenUnknownTransitionType_expectNothingScheduled() = runTest {
        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_DWELL,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun dispatchTransition_givenMissingLocation_expectEntryStillScheduled() = runTest {
        // A missing triggering location can't block a business-geofence
        // transition from being scheduled for delivery.
        val entrySlot = slot<PendingGeofenceDelivery>()

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = null,
            longitude = null
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.geofenceId shouldBeEqualTo "biz-geofence"
    }

    @Test
    fun dispatchTransition_givenCachedRegion_expectNameAndTriggerContextOnEntry() = runTest {
        every { mockStore.getCachedRegion("biz-geofence") } returns
            GeofenceRegion(id = "biz-geofence", latitude = 0.0, longitude = 0.0, radius = 100f, name = "Coffee Shop")
        val entrySlot = slot<PendingGeofenceDelivery>()

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 1) { mockScheduler.schedule(capture(entrySlot)) }
        entrySlot.captured.geofenceName shouldBeEqualTo "Coffee Shop"
        // Testing-only (geofence-testing branch): trigger context populated from the cached region.
        entrySlot.captured.triggerLatitude shouldBeEqualTo 0.0
        entrySlot.captured.triggerLongitude shouldBeEqualTo 0.0
        entrySlot.captured.geofenceRadius shouldBeEqualTo 100.0
        entrySlot.captured.distanceMeters shouldBeEqualTo 0.0
    }

    @Test
    fun dispatchTransition_givenCooldownSuppresses_expectNothingScheduled() = runTest {
        every { mockCooldownFilter.tryAcquire("biz-geofence", Event.GeofenceTransition.ENTER) } returns false

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
    }

    @Test
    fun dispatchTransition_givenCooldownAllows_expectTryAcquireBeforeSchedule() = runTest {
        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerifyOrder {
            mockCooldownFilter.tryAcquire("biz-geofence", Event.GeofenceTransition.ENTER)
            mockScheduler.schedule(any())
        }
    }

    @Test
    fun dispatchTransition_givenBatchWithMovementTriggerAndBusiness_expectOnlyBusinessScheduled() = runTest {
        val scheduled = mutableListOf<PendingGeofenceDelivery>()
        coEvery { mockScheduler.schedule(capture(scheduled)) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID, "biz-geofence"),
            latitude = 0.0,
            longitude = 0.0
        )

        scheduled.map { it.geofenceId } shouldBeEqualTo listOf("biz-geofence")
    }

    @Test
    fun dispatchTransition_givenSchedulerThrows_expectEntryStillRecordedForFlush() = runTest {
        // Append happens before schedule, so a WorkManager scheduling failure
        // still leaves the entry in the store for the foreground flush to deliver.
        coEvery { mockScheduler.schedule(any()) } throws RuntimeException("WM internal")

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-geofence-1"),
            latitude = 1.0,
            longitude = 2.0
        )

        pendingStore.loadAll().single().geofenceId shouldBeEqualTo "biz-geofence-1"
    }

    @Test
    fun dispatchTransition_givenSchedulerThrowsOnFirstGeofence_expectSecondGeofenceStillProcessed() = runTest {
        coEvery { mockScheduler.schedule(match { it.geofenceId == "biz-1" }) } throws RuntimeException("WM internal")
        coEvery { mockScheduler.schedule(match { it.geofenceId == "biz-2" }) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-1", "biz-2"),
            latitude = 0.0,
            longitude = 0.0
        )

        // Both transitions were appended before their schedule attempt.
        pendingStore.loadAll().map { it.geofenceId } shouldBeEqualTo listOf("biz-1", "biz-2")
        coVerify { mockScheduler.schedule(match { it.geofenceId == "biz-2" }) }
    }

    @Test
    fun dispatchTransition_givenIdNotInStore_expectDroppedAndRemovedFromOs() = runTest {
        // Orphan ID must not reach scheduler/cooldown AND must be removed
        // from the OS so it stops firing.
        every { mockStore.getRegisteredIds() } returns setOf("biz-known")

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-orphan"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
        pendingStore.loadAll() shouldBeEqualTo emptyList()
        verify(exactly = 0) { mockCooldownFilter.tryAcquire(any(), any()) }
        coVerify { mockManager.removeGeofencesByIds(listOf("biz-orphan")) }
    }

    @Test
    fun dispatchTransition_givenMovementTriggerNotInStore_expectMovementHandlerNotCalledAndRemoved() = runTest {
        // Movement trigger is only registered when business set is non-empty. If it
        // fires while not in the store, treat it as an orphan: drop + remove.
        every { mockStore.getRegisteredIds() } returns setOf("biz-known")

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_EXIT,
            triggeringGeofenceIds = listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID),
            latitude = 0.0,
            longitude = 0.0
        )

        verify(exactly = 0) { mockServices.onMovementTriggerExit(any(), any()) }
        coVerify { mockManager.removeGeofencesByIds(listOf(GeofenceConstants.MOVEMENT_TRIGGER_ID)) }
    }

    @Test
    fun dispatchTransition_givenMixedKnownAndUnknownIds_expectOnlyKnownProcessedAndUnknownsRemovedAsBatch() = runTest {
        // A single batch can carry both registered and orphan IDs. Per-ID filter,
        // and orphans removed as a single batched GMS call (not one call per orphan).
        every { mockStore.getRegisteredIds() } returns setOf("biz-known")
        val scheduled = mutableListOf<PendingGeofenceDelivery>()
        coEvery { mockScheduler.schedule(capture(scheduled)) } returns Unit

        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-orphan-1", "biz-known", "biz-orphan-2"),
            latitude = 0.0,
            longitude = 0.0
        )

        scheduled.map { it.geofenceId } shouldBeEqualTo listOf("biz-known")
        // Single batched removal call carrying both orphans.
        coVerify(exactly = 1) {
            mockManager.removeGeofencesByIds(listOf("biz-orphan-1", "biz-orphan-2"))
        }
    }

    @Test
    fun dispatchTransition_givenAllIdsKnown_expectNoRemoveCall() = runTest {
        // Normal (no-orphan) path: removeGeofencesByIds must NOT be called when all
        // incoming IDs are tracked.
        receiver.dispatchTransition(
            gmsTransitionType = Geofence.GEOFENCE_TRANSITION_ENTER,
            triggeringGeofenceIds = listOf("biz-1"),
            latitude = 0.0,
            longitude = 0.0
        )

        coVerify(exactly = 0) { mockManager.removeGeofencesByIds(any()) }
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
