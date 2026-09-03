package io.customer.geofence

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Physical containment and durable delivery are two different facts, and storage can fail for either
 * one independently. These tests pin what the device is believed to be *inside* across a full
 * ENTER → EXIT visit while the durable outbox is unavailable — the case where getting containment
 * wrong silently corrupts every later transition for that fence.
 */
@RunWith(RobolectricTestRunner::class)
class GeofenceTransitionStagingContainmentTest : RobolectricTest() {

    private val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val scheduler: GeofenceEventScheduler = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private lateinit var regionStore: GeofenceRegionStoreImpl
    private lateinit var outbox: PendingDeliveryStore<PendingGeofenceDelivery>

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        regionStore = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        regionStore.saveCachedRegions(listOf(region()))
        regionStore.beginUserSession(USER_ID)
        regionStore.saveRegisteredIds(setOf(GEOFENCE_ID))
        regionStore.saveRoutableRegisteredIds(setOf(GEOFENCE_ID))
        every { secureUserStore.getUserId() } returns USER_ID
        every { cooldownFilter.isAllowed(any(), any(), any()) } returns true
        outbox = spyk(
            PendingDeliveryStore(
                context = applicationMock,
                fileName = "cio_test_staging_outbox.json",
                elementSerializer = PendingGeofenceDelivery.serializer(),
                logger = mockk(relaxed = true)
            )
        ).also { it.removeAll() }
    }

    @Test
    fun process_givenOutboxUnavailableAcrossEnterThenExit_expectContainmentTracksPhysicalTruth() = runTest {
        val processor = processor()
        every { outbox.appendAll(any()) } returns false

        // ENTER: the stage commits containment in the same write, so the device is INSIDE even though
        // nothing is deliverable yet.
        processor.process(GEOFENCE_ID, Event.GeofenceTransition.ENTER, timestampSeconds = 100L)

        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
        regionStore.getAllPendingTransitionEntries().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER)

        // EXIT: still nothing deliverable, but the device really did leave. Containment must follow the
        // physical edge; leaving it INSIDE would make the next ENTER look redundant and be dropped.
        processor.process(GEOFENCE_ID, Event.GeofenceTransition.EXIT, timestampSeconds = 200L)

        regionStore.getEnteredIds().shouldBeEmpty()
        regionStore.getAllPendingTransitionEntries().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.EXIT)
    }

    @Test
    fun process_givenOutboxRecoversAfterEnterThenExit_expectBothDeliveredInOrderAndStagingCleared() = runTest {
        val processor = processor()
        every { outbox.appendAll(any()) } returns false
        processor.process(GEOFENCE_ID, Event.GeofenceTransition.ENTER, timestampSeconds = 100L)
        processor.process(GEOFENCE_ID, Event.GeofenceTransition.EXIT, timestampSeconds = 200L)

        every { outbox.appendAll(any()) } answers { callOriginal() }
        processor.recoverPendingTransitions().shouldBeTrue()

        outbox.loadAll().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.EXIT)
        outbox.loadAll().map { it.timestamp } shouldBeEqualTo listOf(100L, 200L)
        regionStore.getAllPendingTransitionEntries().shouldBeEmpty()
        // Recovery only completes the delivery handoff; replaying the older ENTER's containment here
        // would resurrect a visit the device has already left.
        regionStore.getEnteredIds().shouldBeEmpty()
    }

    @Test
    fun process_givenStagingWriteItselfFails_expectContainmentUnchangedSoNothingIsInvented() = runTest {
        val stagingStore = spyk(regionStore) {
            every { savePendingTransitionEntries(any(), any()) } returns false
        }
        val processor = GeofenceBusinessTransitionProcessor(
            store = stagingStore,
            secureUserStore = secureUserStore,
            transitionEmitter = emitter(stagingStore),
            logger = logger
        )

        processor.process(GEOFENCE_ID, Event.GeofenceTransition.ENTER, timestampSeconds = 100L)

        // Neither the event nor the state is durable, so the device must not be believed inside. A
        // later fix re-observes the same edge and stages it again.
        regionStore.getEnteredIds().shouldBeEmpty()
        outbox.loadAll().shouldBeEmpty()
    }

    @Test
    fun process_givenEnterStagedThenExitWhileStagingFails_expectStillBelievedInsideForRetry() = runTest {
        val processor = processor()
        every { outbox.appendAll(any()) } returns false
        processor.process(GEOFENCE_ID, Event.GeofenceTransition.ENTER, timestampSeconds = 100L)
        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)

        val stagingStore = spyk(regionStore) {
            every { savePendingTransitionEntries(any(), any()) } returns false
        }
        GeofenceBusinessTransitionProcessor(
            store = stagingStore,
            secureUserStore = secureUserStore,
            transitionEmitter = emitter(stagingStore),
            logger = logger
        ).process(GEOFENCE_ID, Event.GeofenceTransition.EXIT, timestampSeconds = 200L)

        // The EXIT reached nothing durable, so containment stays INSIDE and the exit remains reportable
        // the next time a fix confirms it. Clearing it here would drop the exit permanently.
        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
    }

    private fun processor() = GeofenceBusinessTransitionProcessor(
        store = regionStore,
        secureUserStore = secureUserStore,
        transitionEmitter = emitter(regionStore),
        logger = logger
    )

    private fun emitter(store: GeofenceRegionStoreImpl) = GeofenceTransitionEmitter(
        cooldownFilter = cooldownFilter,
        pendingStore = outbox,
        scheduler = scheduler.also { coEvery { it.schedule(any()) } returns Unit },
        regionStore = store,
        logger = logger
    )

    private fun region() = GeofenceRegion(
        id = GEOFENCE_ID,
        latitude = 0.0,
        longitude = 0.0,
        radius = 100f,
        transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT)
    )

    private companion object {
        const val USER_ID = "user-1"
        const val GEOFENCE_ID = "biz-1"
    }
}
