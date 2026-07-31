package io.customer.geofence

import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.store.GeofenceRegionStore
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

    // Relaxed default is `hasEmittedEnter = false`, i.e. nothing reported yet — the state every
    // pre-existing test assumes.
    private val mockRegionStore: GeofenceRegionStore = mockk(relaxed = true)

    private val emitter = GeofenceTransitionEmitter(
        cooldownFilter = mockCooldownFilter,
        pendingStore = mockPendingStore,
        scheduler = mockScheduler,
        regionStore = mockRegionStore,
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

    @Test
    fun emit_givenEnterAlreadyReported_expectDroppedWithoutSpendingCooldown() = runTest {
        every { mockRegionStore.hasEmittedEnter("biz-1") } returns true

        val emitted = emitter.emit("biz-1", Event.GeofenceTransition.ENTER, "user-1", 100L, null, emptyMap(), emptyList())

        emitted.shouldBeFalse()
        // Ahead of the cooldown, so the slot stays free for the next genuine transition.
        verify(exactly = 0) { mockCooldownFilter.tryAcquire(any(), any(), any()) }
        verify(exactly = 0) { mockPendingStore.appendAll(any()) }
    }

    @Test
    fun emit_givenExitWhileEnterReported_expectDeliveredAndEnterRearmed() = runTest {
        every { mockRegionStore.hasEmittedEnter("biz-1") } returns true
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true

        val emitted = emitter.emit("biz-1", Event.GeofenceTransition.EXIT, "user-1", 100L, null, emptyMap(), emptyList())

        // The gate is ENTER-only: an EXIT must never be blocked by it.
        emitted.shouldBeTrue()
        verify(exactly = 1) { mockRegionStore.clearEnterEmitted("biz-1") }
    }

    @Test
    fun emit_givenEnterDelivered_expectMarkedReported() = runTest {
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true

        emitter.emit("biz-1", Event.GeofenceTransition.ENTER, "user-1", 100L, null, emptyMap(), emptyList())

        verify(exactly = 1) { mockRegionStore.markEnterEmitted("biz-1") }
    }

    @Test
    fun emit_givenEnterPersistFails_expectNotMarkedSoRetryCanDeliver() = runTest {
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns false

        emitter.emit("biz-1", Event.GeofenceTransition.ENTER, "user-1", 100L, null, emptyMap(), emptyList())

        // Marking a rolled-back write would suppress the retry and lose the crossing entirely.
        verify(exactly = 0) { mockRegionStore.markEnterEmitted(any()) }
    }

    @Test
    fun emit_givenExitPersistFails_expectEnterStaysReported() = runTest {
        every { mockRegionStore.hasEmittedEnter("biz-1") } returns true
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns false

        emitter.emit("biz-1", Event.GeofenceTransition.EXIT, "user-1", 100L, null, emptyMap(), emptyList())

        // Clearing on a failed write would let the next ENTER through while the backend still
        // believes the user is inside.
        verify(exactly = 0) { mockRegionStore.clearEnterEmitted(any()) }
    }

    @Test
    fun emit_givenExitSuppressedByCooldown_expectEnterStaysReported() = runTest {
        every { mockRegionStore.hasEmittedEnter("biz-1") } returns true
        every { mockCooldownFilter.tryAcquire(any(), any(), any()) } returns false

        emitter.emit("biz-1", Event.GeofenceTransition.EXIT, "user-1", 100L, null, emptyMap(), emptyList())

        // A suppressed EXIT was never sent, so the backend's view is unchanged.
        verify(exactly = 0) { mockRegionStore.clearEnterEmitted(any()) }
    }
}
