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
import org.junit.Before
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

    @Before
    fun setUpTransitionStaging() {
        every { mockRegionStore.savePendingTransitionEntries(any(), any()) } returns true
    }

    /** Defaults describe the common case: an ENTER on a fence monitoring both transitions. */
    private suspend fun emit(
        geofenceId: String = "biz-1",
        transition: Event.GeofenceTransition = Event.GeofenceTransition.ENTER,
        userId: String = "user-1",
        geofenceName: String? = null,
        geosetIds: List<String> = emptyList(),
        monitorsExit: Boolean = true
    ): Boolean = emitter.emit(
        geofenceId = geofenceId,
        transition = transition,
        userId = userId,
        timestampSeconds = 100L,
        geofenceName = geofenceName,
        metadata = emptyMap(),
        geosetIds = geosetIds,
        monitorsExit = monitorsExit
    )

    @Test
    fun emit_givenCooldownSuppresses_expectFalseAndNoPersist() = runTest {
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns false

        val emitted = emit()

        emitted.shouldBeFalse()
        verify(exactly = 0) { mockPendingStore.appendAll(any()) }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun emit_givenNoGeosets_expectSingleNullGeosetEntryPersistedAndScheduled() = runTest {
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true
        val entries = slot<List<PendingGeofenceDelivery>>()

        val emitted = emit(geofenceName = "Cafe")

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
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true
        val entries = slot<List<PendingGeofenceDelivery>>()

        // "g1" listed twice → deduped to one entry.
        emit(geosetIds = listOf("g1", "g2", "g1"))

        verify { mockPendingStore.appendAll(capture(entries)) }
        entries.captured.map { it.geosetId } shouldBeEqualTo listOf("g1", "g2")
        entries.captured.map { it.transitionId }.distinct().size shouldBeEqualTo 1
        coVerify(exactly = 2) { mockScheduler.schedule(any()) }
    }

    @Test
    fun emit_givenSchedulerThrowsForOneGeoset_expectRemainingStillScheduled() = runTest {
        // A scheduler failure for one geoset must not abandon the rest of the batch; the row is
        // already persisted, so the foreground flush still delivers the un-scheduled one.
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true
        coEvery { mockScheduler.schedule(match { it.geosetId == "g1" }) } throws RuntimeException("boom")

        val emitted = emit(geosetIds = listOf("g1", "g2"))

        emitted.shouldBeTrue()
        coVerify(exactly = 1) { mockScheduler.schedule(match { it.geosetId == "g2" }) }
        verify { mockLogger.logSchedulerFailed("biz-1", "ENTER", any()) }
    }

    @Test
    fun emit_givenPersistFails_expectCooldownReleasedAndFalse() = runTest {
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns false

        val emitted = emit()

        emitted.shouldBeFalse()
        verify(exactly = 0) { mockCooldownFilter.record(any(), any(), any()) }
        coVerify(exactly = 0) { mockScheduler.schedule(any()) }
    }

    @Test
    fun emit_givenEnterAlreadyReported_expectDroppedWithoutSpendingCooldown() = runTest {
        every { mockRegionStore.hasEmittedEnter("user-1", "biz-1") } returns true

        val emitted = emit()

        emitted.shouldBeFalse()
        // Ahead of the cooldown, so the slot stays free for the next genuine transition.
        verify(exactly = 0) { mockCooldownFilter.isAllowed(any(), any(), any()) }
        verify(exactly = 0) { mockPendingStore.appendAll(any()) }
    }

    @Test
    fun emit_givenEnterOnlyFenceAlreadyReported_expectDelivered() = runTest {
        every { mockRegionStore.hasEmittedEnter("user-1", "biz-1") } returns true
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true

        val emitted = emit(monitorsExit = false)

        // GMS never reports an EXIT for this fence, so nothing would ever clear the mark: honouring
        // it here would suppress every arrival after the first for the life of the registration.
        emitted.shouldBeTrue()
    }

    @Test
    fun emit_givenEnterOnlyFenceDelivered_expectNoMarkRecorded() = runTest {
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true

        emit(monitorsExit = false)

        verify(exactly = 0) { mockRegionStore.markEnterEmitted(any(), any()) }
    }

    @Test
    fun emit_givenExitWhileEnterReported_expectDelivered() = runTest {
        every { mockRegionStore.hasEmittedEnter("user-1", "biz-1") } returns true
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true

        val emitted = emit(transition = Event.GeofenceTransition.EXIT)

        // The gate is ENTER-only: an EXIT must never be blocked by it.
        emitted.shouldBeTrue()
    }

    @Test
    fun emit_givenEnterDelivered_expectStageCommittedAtomically() = runTest {
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true

        emit()

        verify(exactly = 1) { mockRegionStore.savePendingTransitionEntries(any(), any()) }
        verify(exactly = 1) { mockRegionStore.completePendingTransition(any()) }
    }

    @Test
    fun emit_givenEnterPersistFails_expectNotMarkedSoRetryCanDeliver() = runTest {
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns false

        emit()

        // Marking a rolled-back write would suppress the retry and lose the crossing entirely.
        verify(exactly = 0) { mockRegionStore.markEnterEmitted(any(), any()) }
    }

    @Test
    fun emit_givenExitDelivered_expectMarkNotTouchedHere() = runTest {
        every { mockRegionStore.hasEmittedEnter("user-1", "biz-1") } returns true
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        every { mockPendingStore.appendAll(any()) } returns true

        emit(transition = Event.GeofenceTransition.EXIT)

        // Re-arming belongs to `claimExit`, which runs whether or not delivery gets this far.
        verify(exactly = 0) { mockRegionStore.markEnterEmitted(any(), any()) }
    }

    @Test
    fun recoverPendingTransitions_givenCrashBeforeOutboxAppend_expectStableRowsQueuedAndStageCompleted() = runTest {
        val staged = PendingGeofenceDelivery(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 100L,
            userId = "user-1",
            transitionId = "stable-transition",
            marksEnterReported = true
        )
        every { mockRegionStore.getAllPendingTransitionEntries() } returns listOf(staged)
        every { mockPendingStore.appendAll(listOf(staged)) } returns true
        every { mockRegionStore.completePendingTransition("stable-transition") } returns true

        emitter.recoverPendingTransitions()

        verify { mockPendingStore.appendAll(listOf(staged)) }
        verify { mockCooldownFilter.record("user-1", "biz-1", Event.GeofenceTransition.ENTER) }
        coVerify { mockScheduler.schedule(staged) }
        verify { mockRegionStore.completePendingTransition("stable-transition") }
    }

    @Test
    fun recoverPendingTransitions_givenOldUserGeneration_expectDeliversButDoesNotRestoreContainment() = runTest {
        val staged = PendingGeofenceDelivery(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 100L,
            userId = "old-user",
            transitionId = "stable-transition",
            stateGeneration = 4L
        )
        every { mockRegionStore.getAllPendingTransitionEntries() } returns listOf(staged)
        every { mockRegionStore.userStateGeneration() } returns 5L
        every { mockPendingStore.appendAll(listOf(staged)) } returns true

        emitter.recoverPendingTransitions()

        verify { mockPendingStore.appendAll(listOf(staged)) }
        coVerify { mockScheduler.schedule(staged) }
        verify { mockRegionStore.completePendingTransition("stable-transition") }
        verify(exactly = 0) {
            mockRegionStore.commitBusinessTransition(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun recoverPendingTransitions_givenOlderAppendStillFails_expectLaterAttemptDoesNotOvertake() = runTest {
        val enter = PendingGeofenceDelivery(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 100L,
            userId = "user-1",
            transitionId = "enter-transition"
        )
        val exit = enter.copy(
            transition = Event.GeofenceTransition.EXIT,
            timestamp = 101L,
            transitionId = "exit-transition"
        )
        every { mockRegionStore.getAllPendingTransitionEntries() } returns listOf(enter, exit)
        every { mockPendingStore.appendAll(listOf(enter)) } returns false

        emitter.recoverPendingTransitions().shouldBeFalse()

        verify(exactly = 1) { mockPendingStore.appendAll(listOf(enter)) }
        verify(exactly = 0) { mockPendingStore.appendAll(listOf(exit)) }
    }

    @Test
    fun emit_givenOlderAppendStillFails_expectNewOppositeEdgeOnlyStaged() = runTest {
        val enter = PendingGeofenceDelivery(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 99L,
            userId = "user-1",
            transitionId = "enter-transition"
        )
        every { mockRegionStore.getAllPendingTransitionEntries() } returns listOf(enter)
        every { mockPendingStore.appendAll(listOf(enter)) } returns false
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true

        emit(transition = Event.GeofenceTransition.EXIT).shouldBeFalse()

        verify {
            mockRegionStore.savePendingTransitionEntries(
                match { entries -> entries.all { it.transition == Event.GeofenceTransition.EXIT } },
                any()
            )
        }
        verify(exactly = 0) {
            mockPendingStore.appendAll(
                match { entries ->
                    entries.any { it.transition == Event.GeofenceTransition.EXIT }
                }
            )
        }
    }

    @Test
    fun emitWithRetainedAttempt_givenOlderSameDirectionStage_expectNewPhysicalEdgeIsStagedSeparately() = runTest {
        val olderEnter = PendingGeofenceDelivery(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            timestamp = 99L,
            userId = "user-1",
            transitionId = "older-enter"
        )
        every { mockRegionStore.getAllPendingTransitionEntries() } returns listOf(olderEnter)
        every {
            mockRegionStore.getPendingTransitionEntries("user-1", "biz-1", Event.GeofenceTransition.ENTER)
        } returns listOf(olderEnter)
        every { mockPendingStore.appendAll(listOf(olderEnter)) } returns false
        every { mockCooldownFilter.isAllowed(any(), any(), any()) } returns true
        val staged = slot<List<PendingGeofenceDelivery>>()

        emitter.emitWithRetainedAttempt(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            userId = "user-1",
            timestampSeconds = 101L,
            geofenceName = null,
            metadata = emptyMap(),
            geosetIds = emptyList(),
            monitorsExit = true,
            expectedUserStateGeneration = 0L,
            expectedRegionRevision = null
        ) shouldBeEqualTo GeofenceTransitionEmitter.Result.PERSIST_FAILED

        verify { mockRegionStore.savePendingTransitionEntries(capture(staged), 0L) }
        staged.captured.single().let { newest ->
            (newest.transitionId != olderEnter.transitionId).shouldBeTrue()
            newest.timestamp shouldBeEqualTo 101L
        }
    }
}
