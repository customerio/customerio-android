package io.customer.geofence

import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.PendingDeliveryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceTransitionEmitterTest : RobolectricTest() {

    private val mockCooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)
    private val mockPendingStore: PendingDeliveryStore<PendingGeofenceDelivery> = mockk(relaxed = true)
    private val mockScheduler: GeofenceEventScheduler = mockk(relaxed = true)
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)

    private val emitter = GeofenceTransitionEmitter(
        cooldownFilter = mockCooldownFilter,
        pendingStore = mockPendingStore,
        scheduler = mockScheduler,
        logger = mockLogger
    )

    @Test
    fun emit_givenCooldownSuppresses_expectFalseAndNoPersist() = runTest {
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns false

        val emitted = emitter.emit("biz-1", Event.GeofenceTransition.ENTER, "user-1", 100L, null, emptyMap(), emptyList())

        emitted.shouldBeFalse()
        verify(exactly = 0) { mockPendingStore.appendAll(any()) }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun emit_givenNoGeosets_expectSingleNullGeosetEntryPersistedAndScheduled() = runTest {
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true
        val entries = slot<List<PendingGeofenceDelivery>>()

        val emitted = emitter.emit("biz-1", Event.GeofenceTransition.ENTER, "user-1", 100L, "Cafe", emptyMap(), emptyList())

        emitted.shouldBeTrue()
        verify { mockPendingStore.appendAll(capture(entries)) }
        entries.captured.size shouldBeEqualTo 1
        entries.captured[0].geosetId.shouldBeNull()
        entries.captured[0].userId shouldBeEqualTo "user-1"
        entries.captured[0].geofenceName shouldBeEqualTo "Cafe"
        coVerify(exactly = 1) { mockScheduler.schedule(any()) }
    }

    @Test
    fun emit_givenMultipleGeosets_expectPerGeosetFanoutWithSharedTransitionId() = runTest {
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true
        val entries = slot<List<PendingGeofenceDelivery>>()

        // "g1" listed twice → deduped to one entry.
        emitter.emit("biz-1", Event.GeofenceTransition.ENTER, "user-1", 100L, null, emptyMap(), listOf("g1", "g2", "g1"))

        verify { mockPendingStore.appendAll(capture(entries)) }
        entries.captured.map { it.geosetId } shouldBeEqualTo listOf("g1", "g2")
        entries.captured.map { it.transitionId }.distinct().size shouldBeEqualTo 1
        coVerify(exactly = 2) { mockScheduler.schedule(any()) }
    }

    @Test
    fun emit_givenSchedulerThrowsForOneGeoset_expectRemainingStillScheduled() = runTest {
        // A scheduler failure for one geoset must not abandon the rest of the batch; the row is
        // already persisted, so the foreground flush still delivers the un-scheduled one.
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true
        coEvery { mockScheduler.schedule(match { it.geosetId == "g1" }) } throws RuntimeException("boom")

        val emitted = emitter.emit("biz-1", Event.GeofenceTransition.ENTER, "user-1", 100L, null, emptyMap(), listOf("g1", "g2"))

        emitted.shouldBeTrue()
        coVerify(exactly = 1) { mockScheduler.schedule(match { it.geosetId == "g2" }) }
        verify { mockLogger.logSchedulerFailed("biz-1", "ENTER", any()) }
    }

    @Test
    fun emit_givenPersistFails_expectCooldownReleasedAndFalse() = runTest {
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns false

        val emitted = emitter.emit("biz-1", Event.GeofenceTransition.ENTER, "user-1", 100L, null, emptyMap(), emptyList())

        emitted.shouldBeFalse()
        verify(exactly = 1) { mockCooldownFilter.release("user-1", "biz-1", Event.GeofenceTransition.ENTER) }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }
}
