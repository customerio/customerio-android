package io.customer.geofence

import io.customer.geofence.polygon.PolygonBootSessionProvider
import io.customer.geofence.store.GeofenceDwellReservation
import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.GeofenceRegistrationIncarnation
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class GeofenceDwellCoordinatorTest {
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val processor: GeofenceBusinessTransitionProcessor = mockk(relaxed = true)
    private var nowElapsedMs = 0L
    private val clock: Clock = mockk {
        every { elapsedRealtime() } answers { nowElapsedMs }
    }
    private var bootSessionId = "boot-a"
    private val bootSessions = PolygonBootSessionProvider { bootSessionId }

    @Test
    fun confirmedEnterAfterMissedExit_startsANewVisit() = runTest {
        val region = circle()
        var visit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "stale-visit",
            enteredAtSeconds = 100L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 1_000L, beginsNewVisit = true)

        (visit.visitId == "stale-visit") shouldBeEqualTo false
        visit.enteredAtSeconds shouldBeEqualTo 1_000L
    }

    @Test
    fun delayedEnterDoesNotReplaceANewerVisit() = runTest {
        val region = circle()
        val current = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "current-visit",
            enteredAtSeconds = 1_000L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns current
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 999L, beginsNewVisit = true)

        verify(exactly = 0) { store.saveDwellVisit(any()) }
    }

    @Test
    fun duplicateEnterWithTheOriginalFixPreservesTheCurrentVisit() = runTest {
        val region = circle()
        val current = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "current-visit",
            enteredAtSeconds = 100L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS,
            entryFixElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns current
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 800L, beginsNewVisit = false, entryFixElapsedMs = REGISTERED_AT_MS)

        verify(exactly = 0) { store.saveDwellVisit(any()) }
    }

    @Test
    fun weekLongObservedVisitInOneBoot_isReportedOnExit() = runTest {
        // No silence cutoff: an uninterrupted stay is reported however long it lasted.
        val week = 7L * 86_400L
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        val visit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "long-visit",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS,
            entryFixElapsedMs = FRESH_FIX_MS,
            bootSessionId = "boot-a",
            enteredAtElapsedMs = FRESH_FIX_MS + 100L
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns visit
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        val exit = coordinator.onNativeExit(
            "circle",
            exitedAtSeconds = WALL_ENTRY_SECONDS + week,
            triggeringFixElapsedMs = FRESH_FIX_MS + week * 1_000L,
            exitedAtElapsedMs = FRESH_FIX_MS + 100L + week * 1_000L
        )

        exit.visitContext?.visitId shouldBeEqualTo "long-visit"
        exit.visitContext?.durationSeconds shouldBeEqualTo week
        verify { store.removeDwellVisit("circle") }
    }

    @Test
    fun dayLongDwellThreshold_remainsAchievableWhenEvidenceArrivesLate() = runTest {
        val region = circle().copy(dwellThresholdSeconds = 86_400)
        var visit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "visit-1",
            enteredAtSeconds = 100L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS,
            bootSessionId = "boot-a",
            enteredAtElapsedMs = 100_000L
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell(
            "circle",
            observedAtSeconds = 100L + 86_401L,
            triggeringFixElapsedMs = FRESH_FIX_MS,
            observedAtElapsedMs = (100L + 86_401L) * 1_000L
        )

        coVerify {
            processor.process(
                geofenceId = "circle",
                transition = Event.GeofenceTransition.DWELL,
                timestampSeconds = 100L + 86_401L,
                enforceConfiguredTransition = true,
                expectedRegionRevision = region.transitionRevision(),
                expectedUserStateGeneration = 7L,
                requireRegistered = true,
                visitContext = capture(context)
            )
        }
        context.captured.thresholdSeconds shouldBeEqualTo 86_400
        context.captured.durationSeconds shouldBeEqualTo 86_401L
    }

    @Test
    fun nativeDwellWithoutRecordedEnter_emitsOnceWithoutInventingEvidenceMetadata() = runTest {
        val region = circle()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell("circle", observedAtSeconds = 1_000L, triggeringFixElapsedMs = FRESH_FIX_MS)
        coordinator.onNativeDwell("circle", observedAtSeconds = 1_001L, triggeringFixElapsedMs = FRESH_FIX_MS)

        coVerify(exactly = 1) {
            processor.process(
                geofenceId = "circle",
                transition = Event.GeofenceTransition.DWELL,
                timestampSeconds = 1_000L,
                enforceConfiguredTransition = true,
                expectedRegionRevision = region.transitionRevision(),
                expectedUserStateGeneration = 7L,
                requireRegistered = true,
                visitContext = capture(context)
            )
        }
        context.captured.enteredAt.shouldBeNull()
        context.captured.durationSeconds.shouldBeNull()
        visit?.emitted shouldBeEqualTo true
    }

    @Test
    fun nativeDwellAfterDelayedEnter_honorsTheOsThresholdProof() = runTest {
        val region = circle()
        var visit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "visit-1",
            enteredAtSeconds = 105L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS,
            bootSessionId = "boot-a",
            enteredAtElapsedMs = 105_000L
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell(
            "circle",
            observedAtSeconds = 160L,
            triggeringFixElapsedMs = FRESH_FIX_MS,
            observedAtElapsedMs = 160_000L
        )

        coVerify {
            processor.process(
                geofenceId = "circle",
                transition = Event.GeofenceTransition.DWELL,
                timestampSeconds = 160L,
                enforceConfiguredTransition = true,
                expectedRegionRevision = region.transitionRevision(),
                expectedUserStateGeneration = 7L,
                requireRegistered = true,
                visitContext = capture(context)
            )
        }
        context.captured.enteredAt shouldBeEqualTo 105L
        context.captured.durationSeconds.shouldBeNull()
        visit.enteredAtSeconds shouldBeEqualTo 105L
        visit.emitted shouldBeEqualTo true
    }

    @Test
    fun polygonDwell_requiresFreshInsideEvidenceAfterThreshold() = runTest {
        val region = polygon()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("polygon") } returns region
        every { store.userStateGeneration() } returns 2L
        every { store.getDwellVisit("polygon") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true, enteredAtElapsedMs = 100_000L)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 159L, observedAtElapsedMs = 159_000L)
        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }

        coordinator.onInsideEvidence("polygon", observedAtSeconds = 165L, observedAtElapsedMs = 165_000L)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 200L, observedAtElapsedMs = 200_000L)

        coVerify(exactly = 1) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        visit?.emitted shouldBeEqualTo true
    }

    @Test
    fun emittedPolygonDwell_isNotResetByALaterEvidenceGap() = runTest {
        val region = polygon()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("polygon") } returns region
        every { store.userStateGeneration() } returns 2L
        every { store.getDwellVisit("polygon") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true, enteredAtElapsedMs = 100_000L)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 165L, observedAtElapsedMs = 165_000L)
        val emittedVisitId = visit?.visitId
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_061L, observedAtElapsedMs = 1_061_000L)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_121L, observedAtElapsedMs = 1_121_000L)

        coVerify(exactly = 1) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        visit?.visitId shouldBeEqualTo emittedVisitId
        visit?.emitted shouldBeEqualTo true
    }

    @Test
    fun exitContextDoesNotConsumeVisitBeforeTransitionAdmission() = runTest {
        val region = polygon()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("polygon") } returns region
        every { store.userStateGeneration() } returns 2L
        every { store.getDwellVisit("polygon") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true, enteredAtElapsedMs = 100_000L)
        val firstVisitId = visit?.visitId
        coordinator.capturePolygonExit("polygon", exitedAtSeconds = 200L, exitFixElapsedMs = 200_000L)
            .endedVisitId shouldBeEqualTo firstVisitId

        verify(exactly = 0) { store.removeDwellVisit("polygon") }
        visit?.visitId shouldBeEqualTo firstVisitId
    }

    @Test
    fun polygonEvidenceLongAfterTheDeadline_emitsForTheStillValidVisit() = runTest {
        val region = polygon()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("polygon") } returns region
        every { store.userStateGeneration() } returns 2L
        every { store.getDwellVisit("polygon") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true, enteredAtElapsedMs = 100_000L)
        val originalVisitId = visit?.visitId
        coordinator.onInsideEvidence(
            "polygon",
            observedAtSeconds = 100L + 60L + 15L * 60L + 1L,
            observedAtElapsedMs = (100L + 60L + 15L * 60L + 1L) * 1_000L
        )

        coVerify(exactly = 1) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        visit?.visitId shouldBeEqualTo originalVisitId
        visit?.enteredAtSeconds shouldBeEqualTo 100L
        visit?.emitted shouldBeEqualTo true
    }

    @Test
    fun nativeDwellFromPreviousUserGeneration_isDropped() = runTest {
        every { store.userStateGeneration() } returns 8L
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell(
            geofenceId = "circle",
            observedAtSeconds = 1_000L,
            triggeringFixElapsedMs = FRESH_FIX_MS,
            expectedUserStateGeneration = 7L
        )

        verify(exactly = 0) { store.getCachedRegion(any()) }
        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun failedDwellPersistence_retriesWithTheSameVisitId() = runTest {
        val region = circle()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(GeofenceTransitionEmitter.Result.PERSIST_FAILED, GeofenceTransitionEmitter.Result.PERSISTED)
        val contexts = mutableListOf<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell("circle", observedAtSeconds = 1_000L, triggeringFixElapsedMs = FRESH_FIX_MS)
        visit?.emitted shouldBeEqualTo false
        coordinator.onNativeDwell("circle", observedAtSeconds = 1_001L, triggeringFixElapsedMs = FRESH_FIX_MS)

        coVerify(exactly = 2) {
            processor.process(
                geofenceId = "circle",
                transition = Event.GeofenceTransition.DWELL,
                timestampSeconds = any(),
                enforceConfiguredTransition = true,
                expectedRegionRevision = region.transitionRevision(),
                expectedUserStateGeneration = 7L,
                requireRegistered = true,
                visitContext = capture(contexts)
            )
        }
        (contexts[0].visitId == contexts[1].visitId) shouldBeEqualTo true
        visit?.emitted shouldBeEqualTo true
    }

    @Test
    fun exitOnlyFence_tracksVisitAndReturnsObservedDuration() = runTest {
        val region = circle().copy(
            transitionTypes = listOf(GeofenceTransitionType.EXIT),
            dwellThresholdSeconds = 0
        )
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns
            liveRegistration(region).copy(outsideProvenAtElapsedMs = REGISTERED_AT_MS)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter(
            "circle",
            enteredAtSeconds = 100L,
            beginsNewVisit = true,
            entryFixElapsedMs = FRESH_FIX_MS,
            enteredAtElapsedMs = FRESH_FIX_MS + 100L
        )
        val visitId = visit?.visitId
        // GMS decided the departure from a fix 75 s after the entry's, received 75 s later on both clocks.
        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 175L,
            triggeringFixElapsedMs = FRESH_FIX_MS + 75_000L,
            exitedAtElapsedMs = FRESH_FIX_MS + 75_100L
        ).visitContext

        context?.visitId shouldBeEqualTo visitId
        context?.enteredAt shouldBeEqualTo 100L
        context?.durationSeconds shouldBeEqualTo 75L
        context?.detectionSource shouldBeEqualTo "native"
    }

    @Test
    fun exitOnlyFence_discoveredInsideAtRegistrationOmitsDuration() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        every { store.removeDwellVisit("circle") } answers { visit = null }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 100L, beginsNewVisit = true, entryFixElapsedMs = FRESH_FIX_MS)
        visit?.entryWasObserved shouldBeEqualTo false

        coordinator.onNativeExit("circle", exitedAtSeconds = 175L, triggeringFixElapsedMs = FRESH_FIX_MS + 75_000L)
            .visitContext.shouldBeNull()
        visit.shouldBeNull()
    }

    @Test
    fun exitWithoutObservedEntry_omitsVisitContext() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns null
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        val exit = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 175L,
            triggeringFixElapsedMs = FRESH_FIX_MS
        )

        // Still delivered, as an EXIT without visit metadata.
        exit.current shouldBeEqualTo true
        exit.visitContext.shouldBeNull()
    }

    @Test
    fun exitAfterNativeDwellRecoveredALostEnter_omitsEstimatedVisitDuration() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        every { store.removeDwellVisit("circle") } answers { visit = null }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell(
            "circle",
            observedAtSeconds = 1_000L,
            triggeringFixElapsedMs = FRESH_FIX_MS,
            observedAtElapsedMs = FRESH_FIX_MS + 100L
        )
        val exit = coordinator.onNativeExit(
            "circle",
            exitedAtSeconds = 1_075L,
            triggeringFixElapsedMs = FRESH_FIX_MS + 75_000L,
            exitedAtElapsedMs = FRESH_FIX_MS + 75_100L
        )

        exit.visitContext.shouldBeNull()
        visit.shouldBeNull()
    }

    @Test
    fun delayedExitFromOlderVisit_doesNotClearNewerVisit() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        val visit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "new-visit",
            enteredAtSeconds = 200L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns visit
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 175L,
            triggeringFixElapsedMs = FRESH_FIX_MS
        ).visitContext

        context.shouldBeNull()
        verify(exactly = 0) { store.removeDwellVisit("circle") }
    }

    @Test
    fun delayedExitFromReplacedRegistration_neitherEndsNorDescribesTheNewerVisit() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        val newerVisit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "newer-visit",
            enteredAtSeconds = 1_000L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns newerVisit
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        // Received well after the newer visit began, but triggered by a fix the replaced
        // registration evaluated. Receipt time alone would read this as a 500 s visit.
        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 1_500L,
            triggeringFixElapsedMs = REGISTERED_AT_MS - 1L
        ).visitContext

        context.shouldBeNull()
        verify(exactly = 0) { store.removeDwellVisit("circle") }
    }

    @Test
    fun delayedExitFromEarlierVisitOfTheSameRegistration_leavesTheNewerVisit() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        val newerVisit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "newer-visit",
            enteredAtSeconds = 1_000L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS,
            entryFixElapsedMs = FRESH_FIX_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns newerVisit
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 1_000L,
            triggeringFixElapsedMs = FRESH_FIX_MS - 1L
        ).visitContext

        context.shouldBeNull()
        verify(exactly = 0) { store.removeDwellVisit("circle") }
    }

    @Test
    fun exitWithoutTriggeringFix_cannotBeAttributedAndLeavesTheVisit() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        val visit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "visit",
            enteredAtSeconds = 100L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns visit
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        val exit = coordinator.onNativeExit("circle", exitedAtSeconds = 200L, triggeringFixElapsedMs = null)

        // A legacy EXIT without a fix is still delivered, just never attributed to the visit.
        exit.current shouldBeEqualTo true
        exit.visitContext.shouldBeNull()

        verify(exactly = 0) { store.removeDwellVisit("circle") }
    }

    @Test
    fun nativeDwellFromReplacedRegistration_isNotReadAsTheNewThreshold() = runTest {
        // The old registration loitered for 60 s; the live one requires 900 s.
        val region = circle().copy(dwellThresholdSeconds = 900)
        var visit: GeofenceDwellVisit? = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "visit",
            enteredAtSeconds = 1_000L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell(
            "circle",
            observedAtSeconds = 1_100L,
            triggeringFixElapsedMs = REGISTERED_AT_MS - 1L
        )

        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        visit?.emitted shouldBeEqualTo false
    }

    @Test
    fun nativeDwellFromReplacedRegistration_cannotRecoverAMissingVisit() = runTest {
        val region = circle()
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns null
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell("circle", observedAtSeconds = 1_000L, triggeringFixElapsedMs = null)
        coordinator.onNativeDwell(
            "circle",
            observedAtSeconds = 1_000L,
            triggeringFixElapsedMs = REGISTERED_AT_MS - 1L
        )

        verify(exactly = 0) { store.saveDwellVisit(any()) }
        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun unattributedNativeDwell_doesNotEmitMerelyBecauseTimeElapsed() = runTest {
        val region = circle()
        // An EXIT without a triggering fix left this visit in place, so elapsed time says nothing
        // about whether the device is still inside.
        var visit: GeofenceDwellVisit? = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "visit",
            enteredAtSeconds = 1_000L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS,
            entryFixElapsedMs = FRESH_FIX_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        // No fix, then a fix from an earlier visit of the same registration.
        coordinator.onNativeDwell("circle", observedAtSeconds = 5_000L, triggeringFixElapsedMs = null)
        coordinator.onNativeDwell("circle", observedAtSeconds = 5_000L, triggeringFixElapsedMs = FRESH_FIX_MS - 1L)

        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        visit?.emitted shouldBeEqualTo false
    }

    @Test
    fun nativeDwellDecidedBeforeAnObservedExit_cannotRecoverTheEndedStay() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT))
        var incarnation = liveRegistration(region)
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } answers { incarnation }
        every { store.recordNativeExitFix("circle", REGISTERED_AT_MS, any()) } answers {
            incarnation = incarnation.copy(lastExitFixElapsedMs = thirdArg())
        }
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        every { store.removeDwellVisit("circle") } answers { visit = null }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 100L, beginsNewVisit = true, entryFixElapsedMs = FRESH_FIX_MS)
        coordinator.onNativeExit("circle", exitedAtSeconds = 400L, triggeringFixElapsedMs = FRESH_FIX_MS + 300_000L)
        // GMS delivers the visit's DWELL after its EXIT.
        coordinator.onNativeDwell("circle", observedAtSeconds = 401L, triggeringFixElapsedMs = FRESH_FIX_MS + 60_000L)

        visit.shouldBeNull()
        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) }

        // A DWELL decided after the exit is a later stay whose ENTER was lost, and still recovers.
        coordinator.onNativeDwell("circle", observedAtSeconds = 900L, triggeringFixElapsedMs = FRESH_FIX_MS + 800_000L)

        coVerify(exactly = 1) { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        visit?.emitted shouldBeEqualTo true
    }

    @Test
    fun enterRebuildingAVisitAfterContinuityLoss_isNotAnObservedEntry() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT))
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        every { store.removeDwellVisit("circle") } answers { visit = null }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        // Containment already held the fence; the visit was wiped by a monitoring gap and the
        // re-registration's initial trigger reports the device inside.
        coordinator.onEnter("circle", enteredAtSeconds = 5_000L, beginsNewVisit = false)

        visit?.entryWasObserved shouldBeEqualTo false
        coordinator.onNativeExit("circle", exitedAtSeconds = 5_600L, triggeringFixElapsedMs = FRESH_FIX_MS)
            .visitContext.shouldBeNull()
        visit.shouldBeNull()
    }

    @Test
    fun polygonInsideEvidenceRebuildingAVisit_reportsNoObservedEntryOrExitDuration() = runTest {
        val region = polygon().copy(
            transitionTypes = listOf(GeofenceTransitionType.ENTER, GeofenceTransitionType.EXIT)
        )
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("polygon") } returns region
        every { store.userStateGeneration() } returns 2L
        every { store.getDwellVisit("polygon") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_000L, observedAtElapsedMs = 1_000_000L)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_061L, observedAtElapsedMs = 1_061_000L)

        visit?.entryWasObserved shouldBeEqualTo false
        coVerify(exactly = 1) {
            processor.process(
                geofenceId = "polygon",
                transition = Event.GeofenceTransition.DWELL,
                timestampSeconds = 1_061L,
                enforceConfiguredTransition = true,
                expectedRegionRevision = region.transitionRevision(),
                expectedUserStateGeneration = 2L,
                requireRegistered = true,
                visitContext = capture(context),
                endsVisitByTimestamp = any()
            )
        }
        context.captured.enteredAt.shouldBeNull()
        context.captured.durationSeconds.shouldBeNull()
        coordinator.capturePolygonExit("polygon", exitedAtSeconds = 1_200L, exitFixElapsedMs = 1_200_000L)
            .visitContext.shouldBeNull()
    }

    @Test
    fun visitFromAnEarlierRegistration_isDroppedOnceTheFenceIsReRegistered() = runTest {
        val region = circle()
        var visit: GeofenceDwellVisit? = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "pre-gap-visit",
            enteredAtSeconds = 100L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS - 5_000L
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.removeDwellVisit("circle") } answers { visit = null }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeExit("circle", exitedAtSeconds = 5_000L, triggeringFixElapsedMs = FRESH_FIX_MS)
            .visitContext.shouldBeNull()

        verify { store.removeDwellVisit("circle") }
    }

    @Test
    fun circleEnterWithoutALiveRegistration_startsNoVisit() = runTest {
        val region = circle()
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns null
        every { store.userStateGeneration() } returns 7L
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 100L, beginsNewVisit = true, entryFixElapsedMs = FRESH_FIX_MS)

        verify(exactly = 0) { store.saveDwellVisit(any()) }
    }

    @Test
    fun nativeEnterWithoutOutsideProof_replacesTheVisitButReportsNoObservedEntry() = runTest {
        // GMS's initial trigger for a fence registered around a device already inside (boot restore,
        // anchor refresh, or a delayed ENTER after containment was cleared) carries a fresh fix but
        // no crossing. The DWELL it leads to keeps its visit ID and omits entry time and duration.
        val region = circle()
        var visit: GeofenceDwellVisit? = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "stale-candidate",
            enteredAtSeconds = 50L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            entryWasObserved = false,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 100L, beginsNewVisit = true, entryFixElapsedMs = FRESH_FIX_MS)
        coordinator.onNativeDwell("circle", observedAtSeconds = 200L, triggeringFixElapsedMs = FRESH_FIX_MS + 60_000L)

        (visit?.visitId == "stale-candidate") shouldBeEqualTo false
        visit?.entryWasObserved shouldBeEqualTo false
        coVerify(exactly = 1) {
            processor.process(
                geofenceId = "circle",
                transition = Event.GeofenceTransition.DWELL,
                timestampSeconds = 200L,
                enforceConfiguredTransition = true,
                expectedRegionRevision = region.transitionRevision(),
                expectedUserStateGeneration = 7L,
                requireRegistered = true,
                visitContext = capture(context),
                endsVisitByTimestamp = any()
            )
        }
        context.captured.visitId shouldBeEqualTo visit?.visitId
        context.captured.enteredAt.shouldBeNull()
        context.captured.durationSeconds.shouldBeNull()
    }

    @Test
    fun enterAfterRegistrationProvedOutside_reportsObservedEntryAndDuration() = runTest {
        val region = circle()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns
            liveRegistration(region).copy(outsideProvenAtElapsedMs = REGISTERED_AT_MS)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter(
            "circle",
            enteredAtSeconds = 100L,
            beginsNewVisit = true,
            entryFixElapsedMs = FRESH_FIX_MS,
            enteredAtElapsedMs = 100_000L
        )
        coordinator.onNativeDwell(
            "circle",
            observedAtSeconds = 170L,
            triggeringFixElapsedMs = FRESH_FIX_MS + 60_000L,
            observedAtElapsedMs = 170_000L
        )

        coVerify(exactly = 1) {
            processor.process(any(), any(), any(), any(), any(), any(), any(), capture(context), any())
        }
        context.captured.enteredAt shouldBeEqualTo 100L
        context.captured.durationSeconds shouldBeEqualTo 70L
    }

    @Test
    fun enterWhoseFixDoesNotPostdateTheOutsideProof_isNotAnObservedEntry() = runTest {
        // An ENTER decided at or before the fix of an attributed EXIT describes an earlier stay.
        assertEntryObserved(outsideProvenAt = FRESH_FIX_MS, entryFix = FRESH_FIX_MS, expected = false)
        assertEntryObserved(outsideProvenAt = FRESH_FIX_MS, entryFix = FRESH_FIX_MS - 1L, expected = false)
    }

    @Test
    fun enterWithoutATriggeringFix_isNotAnObservedEntry() = runTest {
        assertEntryObserved(outsideProvenAt = REGISTERED_AT_MS, entryFix = null, expected = false)
    }

    @Test
    fun enterAfterAnAttributedExit_isAnObservedEntry() = runTest {
        // Registered with the device inside, so only the attributed EXIT proves it left. The next
        // ENTER is then a genuine crossing.
        val region = circle()
        var incarnation = liveRegistration(region)
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } answers { incarnation }
        every { store.recordNativeExitFix("circle", REGISTERED_AT_MS, any()) } answers {
            incarnation = incarnation.copy(lastExitFixElapsedMs = thirdArg(), outsideProvenAtElapsedMs = thirdArg())
        }
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        every { store.removeDwellVisit("circle") } answers { visit = null }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 100L)
        visit?.entryWasObserved shouldBeEqualTo false
        coordinator.onNativeExit("circle", exitedAtSeconds = 400L, triggeringFixElapsedMs = FRESH_FIX_MS)
        coordinator.onEnter("circle", enteredAtSeconds = 900L, beginsNewVisit = true, entryFixElapsedMs = FRESH_FIX_MS + 1L)

        visit?.enteredAtSeconds shouldBeEqualTo 900L
        visit?.entryWasObserved shouldBeEqualTo true
    }

    @Test
    fun polygonEnter_isObservedOnlyWhenTheEvaluatorSawTheDeviceOutside() = runTest {
        val region = polygon()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("polygon") } returns region
        every { store.userStateGeneration() } returns 2L
        every { store.getDwellVisit("polygon") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true)
        visit?.entryWasObserved shouldBeEqualTo false

        coordinator.onEnter("polygon", enteredAtSeconds = 200L, beginsNewVisit = true, polygonOutsideObserved = true)
        visit?.entryWasObserved shouldBeEqualTo true
    }

    @Test
    fun repeatedEnterAfterOutsideProofSinceTheVisit_startsANewObservedVisit() = runTest {
        // EXIT lost, so containment still holds the fence. The registration then proved the device
        // outside after the visit's entry, and GMS decided this ENTER after that proof.
        val state = repeatedEnterState(outsideProvenAt = FRESH_FIX_MS + 10_000L)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter(
            "circle",
            enteredAtSeconds = 900L,
            entryFixElapsedMs = FRESH_FIX_MS + 20_000L,
            enteredAtElapsedMs = 900_000L
        )

        val restarted = state.visit
        (restarted?.visitId == "visit-1") shouldBeEqualTo false
        restarted?.enteredAtSeconds shouldBeEqualTo 900L
        restarted?.emitted shouldBeEqualTo false
        restarted?.entryWasObserved shouldBeEqualTo true
        restarted?.entryFixElapsedMs shouldBeEqualTo FRESH_FIX_MS + 20_000L

        coordinator.onNativeDwell(
            "circle",
            observedAtSeconds = 960L,
            triggeringFixElapsedMs = FRESH_FIX_MS + 80_000L,
            observedAtElapsedMs = 960_000L
        )

        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        coVerify(exactly = 1) {
            processor.process(any(), any(), any(), any(), any(), any(), any(), capture(context), any())
        }
        context.captured.visitId shouldBeEqualTo restarted?.visitId
        context.captured.enteredAt shouldBeEqualTo 900L
        context.captured.durationSeconds shouldBeEqualTo 60L
    }

    @Test
    fun repeatedEnterWithoutOutsideProof_keepsTheEmittedVisit() = runTest {
        // GMS can re-report ENTER without the device ever leaving, so the ENTER alone splits nothing.
        val state = repeatedEnterState(outsideProvenAt = null)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = 900L, entryFixElapsedMs = FRESH_FIX_MS + 20_000L)
        coordinator.onNativeDwell("circle", observedAtSeconds = 960L, triggeringFixElapsedMs = FRESH_FIX_MS + 80_000L)

        state.visit?.visitId shouldBeEqualTo "visit-1"
        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun repeatedEnterWhoseFixDoesNotPostdateTheOutsideProof_keepsTheVisit() = runTest {
        // Duplicate delivery carrying the visit's own entry fix, then ENTERs decided at or before
        // the proof, which cannot describe an arrival after it.
        for (entryFix in listOf(FRESH_FIX_MS, FRESH_FIX_MS + 5_000L, FRESH_FIX_MS + 10_000L, null)) {
            val state = repeatedEnterState(outsideProvenAt = FRESH_FIX_MS + 10_000L)

            GeofenceDwellCoordinator(store, processor, clock, bootSessions)
                .onEnter("circle", enteredAtSeconds = 900L, entryFixElapsedMs = entryFix)

            state.visit?.visitId shouldBeEqualTo "visit-1"
        }
    }

    @Test
    fun outsideProofNotAfterTheVisitsEntry_doesNotSplitIt() = runTest {
        // A proof from before this stay began, such as the registration's own outside fix or an
        // out-of-order EXIT from an earlier stay, says nothing about leaving this one.
        for (proof in listOf(FRESH_FIX_MS - 1L, FRESH_FIX_MS)) {
            val state = repeatedEnterState(outsideProvenAt = proof)

            GeofenceDwellCoordinator(store, processor, clock, bootSessions)
                .onEnter("circle", enteredAtSeconds = 900L, entryFixElapsedMs = FRESH_FIX_MS + 20_000L)

            state.visit?.visitId shouldBeEqualTo "visit-1"
        }
    }

    @Test
    fun visitWithoutAnInsideFix_isNotSplitByOutsideProof() = runTest {
        // Started by an ENTER with no triggering fix: nothing orders the proof after the stay began.
        val state = repeatedEnterState(outsideProvenAt = FRESH_FIX_MS + 10_000L, visitEntryFix = null)

        GeofenceDwellCoordinator(store, processor, clock, bootSessions)
            .onEnter("circle", enteredAtSeconds = 900L, entryFixElapsedMs = FRESH_FIX_MS + 20_000L)

        state.visit?.visitId shouldBeEqualTo "visit-1"
    }

    @Test
    fun outsideProofAfterTheVisitsInsideEvidence_isNotOverruledByALaterDwell() = runTest {
        // Outside proof clears the edge by the fix's accuracy plus a margin, so a later GMS DWELL
        // shows the device came back, not that it never left. GMS reported no ENTER since, so its
        // loitering may still count from this visit's entry: the DWELL qualifies nothing.
        val state = repeatedEnterState(outsideProvenAt = FRESH_FIX_MS + 10_000L)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell("circle", observedAtSeconds = 400L, triggeringFixElapsedMs = FRESH_FIX_MS + 15_000L)

        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        state.visit?.lastInsideFixElapsedMs.shouldBeNull()
        state.visit?.entryWasObserved shouldBeEqualTo false
        // GMS's ENTER after the proof is the re-entry, and starts a new visit.
        coordinator.onEnter("circle", enteredAtSeconds = 900L, entryFixElapsedMs = FRESH_FIX_MS + 20_000L)

        (state.visit?.visitId == "visit-1") shouldBeEqualTo false
    }

    @Test
    fun outsideProofAfterTheLastAttributedDwell_stillSplits() = runTest {
        val state = repeatedEnterState(outsideProvenAt = FRESH_FIX_MS + 20_000L)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeDwell("circle", observedAtSeconds = 400L, triggeringFixElapsedMs = FRESH_FIX_MS + 15_000L)
        coordinator.onEnter("circle", enteredAtSeconds = 900L, entryFixElapsedMs = FRESH_FIX_MS + 30_000L)

        (state.visit?.visitId == "visit-1") shouldBeEqualTo false
    }

    @Test
    fun enterDecidedBeforeTheDwellThatRecoveredTheVisit_keepsIt() = runTest {
        // ENTER lost in process death, DWELL recovered and emitted the visit, then the ENTER's
        // broadcast arrived late. DWELL commits no containment, so this ENTER reports a new visit.
        val region = circle()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)
        coordinator.onNativeDwell("circle", observedAtSeconds = 1_000L, triggeringFixElapsedMs = FRESH_FIX_MS)
        val recovered = visit

        coordinator.onEnter("circle", enteredAtSeconds = 1_005L, beginsNewVisit = true, entryFixElapsedMs = FRESH_FIX_MS - 5_000L)

        visit shouldBeEqualTo recovered
        visit?.emitted shouldBeEqualTo true

        // A later ENTER still restarts it, as containment never held the fence.
        coordinator.onEnter("circle", enteredAtSeconds = 2_000L, beginsNewVisit = true, entryFixElapsedMs = FRESH_FIX_MS + 1L)
        (visit?.visitId == recovered?.visitId) shouldBeEqualTo false
    }

    @Test
    fun legacyVisitWithoutLastInsideFix_decodesWithNone() {
        val legacy = """{"geofenceId":"circle","visitId":"v","enteredAtSeconds":1,"regionRevision":2,""" +
            """"userStateGeneration":3,"emitted":true,"registrationElapsedMs":4,"entryFixElapsedMs":5}"""

        val decoded = GeofenceJsonSerializer().decode(GeofenceDwellVisit.serializer(), legacy)

        decoded.entryFixElapsedMs shouldBeEqualTo 5L
        decoded.lastInsideFixElapsedMs.shouldBeNull()
    }

    private class RepeatedEnterState(var visit: GeofenceDwellVisit?)

    /** An emitted visit whose EXIT was lost, entered at [visitEntryFix] under the live registration. */
    @Test
    fun polygonDwell_whenTheWallClockStepsForward_measuresTheThresholdOnTheBootClock() = runTest {
        val region = polygon().copy(dwellThresholdSeconds = 600)
        val visit = statefulVisitStore(region)
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), capture(context)) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)
        coordinator.onEnter(
            "polygon",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            beginsNewVisit = true,
            polygonOutsideObserved = true,
            enteredAtElapsedMs = 50_000L
        )

        // 90 s of real time, but the wall clock jumped forward an hour in between.
        coordinator.onInsideEvidence(
            "polygon",
            observedAtSeconds = WALL_ENTRY_SECONDS + 3_690L,
            observedAtElapsedMs = 140_000L
        )

        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }

        coordinator.onInsideEvidence(
            "polygon",
            observedAtSeconds = WALL_ENTRY_SECONDS + 4_200L,
            observedAtElapsedMs = 650_000L
        )

        coVerify(exactly = 1) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        // 600 s passed on the boot clock and 4 200 s on the wall clock, so the wall entry time
        // cannot be paired with either.
        context.captured.enteredAt.shouldBeNull()
        context.captured.durationSeconds.shouldBeNull()
        visit()?.emitted shouldBeEqualTo true
    }

    @Test
    fun polygonDwell_whenBothClocksAgree_reportsTheBootClockDuration() = runTest {
        val region = polygon().copy(dwellThresholdSeconds = 600)
        statefulVisitStore(region)
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), capture(context)) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)
        coordinator.onEnter(
            "polygon",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            beginsNewVisit = true,
            polygonOutsideObserved = true,
            enteredAtElapsedMs = 50_000L
        )

        coordinator.onInsideEvidence(
            "polygon",
            observedAtSeconds = WALL_ENTRY_SECONDS + 601L,
            observedAtElapsedMs = 650_400L
        )

        context.captured.enteredAt shouldBeEqualTo WALL_ENTRY_SECONDS
        context.captured.durationSeconds shouldBeEqualTo 600L
    }

    @Test
    fun polygonVisitFromAnEarlierBoot_isNotResumedEvenWhenUptimeHasPassedIt() = runTest {
        // BOOT_COMPLETED never arrived, and the new boot has been up longer than the old visit's
        // stamp, so uptime alone cannot tell the two boots apart.
        val region = polygon()
        val visit = statefulVisitStore(
            region,
            GeofenceDwellVisit(
                geofenceId = "polygon",
                visitId = "previous-boot",
                enteredAtSeconds = WALL_ENTRY_SECONDS,
                regionRevision = region.transitionRevision(),
                userStateGeneration = 7L,
                bootSessionId = "boot-a",
                enteredAtElapsedMs = 10_000L
            )
        )
        bootSessionId = "boot-b"
        nowElapsedMs = 900_000L
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onInsideEvidence(
            "polygon",
            observedAtSeconds = WALL_ENTRY_SECONDS + 900L,
            observedAtElapsedMs = 900_000L
        )

        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        (visit()?.visitId == "previous-boot") shouldBeEqualTo false
        visit()?.entryWasObserved shouldBeEqualTo false
        visit()?.bootSessionId shouldBeEqualTo "boot-b"
    }

    @Test
    fun polygonVisitWithoutTimingProvenance_restartsAsACandidateFromTheEvidence() = runTest {
        val region = polygon()
        val visit = statefulVisitStore(
            region,
            GeofenceDwellVisit(
                geofenceId = "polygon",
                visitId = "legacy",
                enteredAtSeconds = 100L,
                regionRevision = region.transitionRevision(),
                userStateGeneration = 7L
            )
        )
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), capture(context)) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_000L, observedAtElapsedMs = 30_000L)

        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        visit()?.enteredAtSeconds shouldBeEqualTo 1_000L
        visit()?.enteredAtElapsedMs shouldBeEqualTo 30_000L
        visit()?.entryWasObserved shouldBeEqualTo false

        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_060L, observedAtElapsedMs = 90_000L)

        context.captured.enteredAt.shouldBeNull()
        context.captured.durationSeconds.shouldBeNull()
    }

    @Test
    fun nativeVisitsAcrossABackwardWallClockStep_areOrderedByTheirFixes() = runTest {
        // (wall, fix): ENTER(W, 1 000), EXIT(W - 3 600, 2 000), ENTER(W - 3 000, 3 000),
        // EXIT(W + 600, 9 000). The clock was an hour fast at the first ENTER, then corrected.
        val region = circle()
        val visit = statefulVisitStore(region)
        every { store.getRegistrationIncarnation("circle") } returns
            liveRegistration(region).copy(registeredAtElapsedMs = 500L)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter(
            "circle",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            beginsNewVisit = true,
            entryFixElapsedMs = 1_000L,
            enteredAtElapsedMs = 1_100L
        )
        val first = visit()?.visitId
        coordinator.onNativeExit(
            "circle",
            WALL_ENTRY_SECONDS - 3_600L,
            triggeringFixElapsedMs = 2_000L,
            exitedAtElapsedMs = 2_100L
        ).current shouldBeEqualTo true

        visit().shouldBeNull()

        coordinator.onEnter(
            "circle",
            enteredAtSeconds = WALL_ENTRY_SECONDS - 3_000L,
            beginsNewVisit = true,
            entryFixElapsedMs = 3_000L,
            enteredAtElapsedMs = 3_100L
        )
        val second = visit()?.visitId
        (second != null && second != first) shouldBeEqualTo true
        coordinator.onNativeExit("circle", WALL_ENTRY_SECONDS + 600L, triggeringFixElapsedMs = 9_000L, exitedAtElapsedMs = 9_100L)

        visit().shouldBeNull()
    }

    @Test
    fun nativeExitOlderThanTheVisitsLatestDwellFix_isSupersededAndKeepsTheVisit() = runTest {
        val region = circle()
        val current = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "current",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = 500L,
            entryFixElapsedMs = 1_000L,
            lastInsideFixElapsedMs = 5_000L,
            bootSessionId = "boot-a",
            enteredAtElapsedMs = 1_100L
        )
        val visit = statefulVisitStore(region, current)
        every { store.getRegistrationIncarnation("circle") } returns
            liveRegistration(region).copy(registeredAtElapsedMs = 500L)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeExit(
            "circle",
            WALL_ENTRY_SECONDS + 80L,
            triggeringFixElapsedMs = 2_000L,
            exitedAtElapsedMs = 80_000L
        ).current shouldBeEqualTo false

        // Kept, with only the entry withdrawn: the device was outside between it and the DWELL.
        visit() shouldBeEqualTo current.copy(entryWasObserved = false)
        verify(exactly = 0) { store.recordNativeExitFix(any(), any(), any()) }

        // A departure decided after the latest inside fix still ends the visit.
        coordinator.onNativeExit(
            "circle",
            WALL_ENTRY_SECONDS + 90L,
            triggeringFixElapsedMs = 6_000L,
            exitedAtElapsedMs = 90_000L
        ).current shouldBeEqualTo true

        visit().shouldBeNull()
    }

    @Test
    fun supersededExitBetweenTheEntryAndANewerInsideFix_withdrawsOnlyTheObservedEntry() = runTest {
        // A tie with the entry fix is ambiguous, so it withdraws the entry too.
        for (exitFix in listOf(2_000L, 1_000L)) {
            val region = circle()
            val reservation = GeofenceDwellReservation(
                timestampSeconds = WALL_ENTRY_SECONDS + 70L,
                enteredAt = WALL_ENTRY_SECONDS,
                thresholdSeconds = 60,
                durationSeconds = 70L,
                detectionSource = "native"
            )
            val current = GeofenceDwellVisit(
                geofenceId = "circle",
                visitId = "current",
                enteredAtSeconds = WALL_ENTRY_SECONDS,
                regionRevision = region.transitionRevision(),
                userStateGeneration = 7L,
                emitted = true,
                entryWasObserved = true,
                registrationElapsedMs = 500L,
                entryFixElapsedMs = 1_000L,
                lastInsideFixElapsedMs = 5_000L,
                bootSessionId = "boot-a",
                enteredAtElapsedMs = 1_100L,
                dwellReservation = reservation
            )
            val visit = statefulVisitStore(region, current)
            every { store.getRegistrationIncarnation("circle") } returns
                liveRegistration(region).copy(registeredAtElapsedMs = 500L)
            val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

            coordinator.onNativeExit(
                "circle",
                WALL_ENTRY_SECONDS + 80L,
                triggeringFixElapsedMs = exitFix,
                exitedAtElapsedMs = 80_000L
            ).current shouldBeEqualTo false

            // Still the same stay as far as containment, the queued DWELL and its retry know.
            visit() shouldBeEqualTo current.copy(entryWasObserved = false)
        }
    }

    @Test
    fun exitWithoutAFixReceivedBeforeTheVisitBegan_keepsTheObservedEntry() = runTest {
        // Control: by receipt this EXIT predates the visit's ENTER, so it says nothing about this stay.
        val region = circle()
        val current = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "current",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            entryWasObserved = true,
            registrationElapsedMs = REGISTERED_AT_MS,
            entryFixElapsedMs = FRESH_FIX_MS,
            bootSessionId = "boot-a",
            enteredAtElapsedMs = 30_000L
        )
        val visit = statefulVisitStore(region, current)
        every { store.getEnteredIds() } returns setOf("circle")
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onNativeExit(
            "circle",
            WALL_ENTRY_SECONDS - 5L,
            triggeringFixElapsedMs = null,
            exitedAtElapsedMs = 25_000L
        ).current shouldBeEqualTo true

        visit() shouldBeEqualTo current
    }

    @Test
    fun supersededExitOlderThanTheEntryFix_keepsTheObservedEntry() = runTest {
        val region = circle()
        val current = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "current",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            entryWasObserved = true,
            registrationElapsedMs = 500L,
            entryFixElapsedMs = 1_000L,
            lastInsideFixElapsedMs = 5_000L,
            bootSessionId = "boot-a",
            enteredAtElapsedMs = 1_100L
        )
        val visit = statefulVisitStore(region, current)
        every { store.getRegistrationIncarnation("circle") } returns
            liveRegistration(region).copy(registeredAtElapsedMs = 500L)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        // Decided before the entry, so it is the departure the entry then followed.
        coordinator.onNativeExit(
            "circle",
            WALL_ENTRY_SECONDS + 80L,
            triggeringFixElapsedMs = 900L,
            exitedAtElapsedMs = 80_000L
        ).current shouldBeEqualTo false

        visit() shouldBeEqualTo current
    }

    @Test
    fun enterDecidedBeforeItsRegistrationsRecordedExit_startsNoVisit() = runTest {
        // The pipeline committed this ENTER, then an EXIT decided at 2 000 ran to completion before
        // the ENTER's visit write.
        val region = circle()
        statefulVisitStore(region)
        every { store.getRegistrationIncarnation("circle") } returns
            liveRegistration(region).copy(registeredAtElapsedMs = 500L, lastExitFixElapsedMs = 2_000L)
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

        coordinator.onEnter("circle", enteredAtSeconds = WALL_ENTRY_SECONDS, beginsNewVisit = true, entryFixElapsedMs = 1_000L)

        verify(exactly = 0) { store.saveDwellVisit(any()) }
    }

    @Test
    fun nativeCallbacksAfterAReboot_areNotAttributedToTheEarlierBootsRegistration() = runTest {
        // BOOT_COMPLETED never arrived and the new boot has been up longer than the old
        // registration's stamp, so the numbers alone would attribute this fix to it.
        val region = circle()
        statefulVisitStore(region)
        for (stampedIn in listOf("boot-a", null)) {
            every { store.getRegistrationIncarnation("circle") } returns
                liveRegistration(region).copy(bootSessionId = stampedIn, outsideProvenAtElapsedMs = REGISTERED_AT_MS)
            bootSessionId = "boot-b"
            val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)

            coordinator.onNativeDwell("circle", observedAtSeconds = 2_000L, triggeringFixElapsedMs = 900_000L)
            coordinator.onEnter("circle", enteredAtSeconds = 2_100L, beginsNewVisit = true, entryFixElapsedMs = 905_000L)
            coordinator.onNativeExit("circle", exitedAtSeconds = 2_200L, triggeringFixElapsedMs = 910_000L)
        }

        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        // In particular, the earlier boot's outside proof cannot make this ENTER an observed entry.
        verify(exactly = 0) { store.saveDwellVisit(any()) }
        verify(exactly = 0) { store.recordNativeExitFix(any(), any(), any()) }
    }

    @Test
    fun polygonDwell_whenTheWallClockStepsByLessThanAMinute_omitsEntryMetadata() = runTest {
        val region = polygon().copy(dwellThresholdSeconds = 600)
        statefulVisitStore(region)
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), capture(context)) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor, clock, bootSessions)
        coordinator.onEnter(
            "polygon",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            beginsNewVisit = true,
            polygonOutsideObserved = true,
            enteredAtElapsedMs = 50_000L
        )

        // 600 s on the boot clock, 630 s on the wall clock: a 30 s step, not rounding.
        coordinator.onInsideEvidence(
            "polygon",
            observedAtSeconds = WALL_ENTRY_SECONDS + 630L,
            observedAtElapsedMs = 650_000L
        )

        context.captured.enteredAt.shouldBeNull()
        context.captured.durationSeconds.shouldBeNull()
    }

    /** Backs the mocked store's visit with a variable; returns its reader. */
    private fun statefulVisitStore(
        region: GeofenceRegion,
        initial: GeofenceDwellVisit? = null
    ): () -> GeofenceDwellVisit? {
        var visit = initial
        every { store.getCachedRegion(region.id) } returns region
        every { store.getRegistrationIncarnation(region.id) } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit(region.id) } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        every { store.removeDwellVisit(region.id) } answers { visit = null }
        return { visit }
    }

    private fun repeatedEnterState(
        outsideProvenAt: Long?,
        visitEntryFix: Long? = FRESH_FIX_MS
    ): RepeatedEnterState {
        val region = circle()
        val state = RepeatedEnterState(
            GeofenceDwellVisit(
                geofenceId = "circle",
                visitId = "visit-1",
                enteredAtSeconds = 100L,
                regionRevision = region.transitionRevision(),
                userStateGeneration = 7L,
                emitted = true,
                registrationElapsedMs = REGISTERED_AT_MS,
                entryFixElapsedMs = visitEntryFix
            )
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns
            liveRegistration(region).copy(outsideProvenAtElapsedMs = outsideProvenAt)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { state.visit }
        every { store.saveDwellVisit(any()) } answers {
            state.visit = firstArg()
            true
        }
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        return state
    }

    private suspend fun assertEntryObserved(outsideProvenAt: Long, entryFix: Long?, expected: Boolean) {
        val region = circle()
        var visit: GeofenceDwellVisit? = null
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns
            liveRegistration(region).copy(outsideProvenAtElapsedMs = outsideProvenAt)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        GeofenceDwellCoordinator(store, processor, clock, bootSessions)
            .onEnter("circle", enteredAtSeconds = 100L, beginsNewVisit = true, entryFixElapsedMs = entryFix)
        visit?.entryWasObserved shouldBeEqualTo expected
    }

    @Test
    fun nativeExit_reportsTheWallClockSpanOnlyWhileItAgreesWithTheBootClock() = runTest {
        // 75 s on the boot clock. Whole-second stamps and the two clock reads allow 2 s of
        // difference; anything more is a clock step.
        nativeExitDurationForWallSpan(75L) shouldBeEqualTo 75L
        nativeExitDurationForWallSpan(77L) shouldBeEqualTo 77L
        nativeExitDurationForWallSpan(78L).shouldBeNull()
        nativeExitDurationForWallSpan(73L) shouldBeEqualTo 73L
        nativeExitDurationForWallSpan(72L).shouldBeNull()
    }

    private suspend fun nativeExitDurationForWallSpan(wallSpanSeconds: Long): Long? {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "visit",
            enteredAtSeconds = WALL_ENTRY_SECONDS,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS,
            entryFixElapsedMs = FRESH_FIX_MS,
            bootSessionId = "boot-a",
            enteredAtElapsedMs = FRESH_FIX_MS + 100L
        )
        return GeofenceDwellCoordinator(store, processor, clock, bootSessions).onNativeExit(
            "circle",
            exitedAtSeconds = WALL_ENTRY_SECONDS + wallSpanSeconds,
            triggeringFixElapsedMs = FRESH_FIX_MS + 75_000L,
            exitedAtElapsedMs = FRESH_FIX_MS + 75_100L
        ).visitContext?.durationSeconds
    }

    private fun liveRegistration(region: GeofenceRegion) = GeofenceRegistrationIncarnation(
        geofenceId = region.id,
        regionRevision = region.transitionRevision(),
        registeredAtElapsedMs = REGISTERED_AT_MS,
        bootSessionId = "boot-a"
    )

    private fun circle() = GeofenceRegion(
        id = "circle",
        latitude = 1.0,
        longitude = 2.0,
        radius = 100f,
        dwellThresholdSeconds = 60
    )

    private fun polygon() = GeofenceRegion(
        id = "polygon",
        latitude = 1.0,
        longitude = 2.0,
        radius = 100f,
        polygonVertices = listOf(
            io.customer.geofence.polygon.PolygonCoordinate(0.0, 0.0),
            io.customer.geofence.polygon.PolygonCoordinate(0.0, 1.0),
            io.customer.geofence.polygon.PolygonCoordinate(1.0, 0.0),
            io.customer.geofence.polygon.PolygonCoordinate(0.0, 0.0)
        ),
        dwellThresholdSeconds = 60
    )

    private companion object {
        const val REGISTERED_AT_MS = 10_000L
        const val FRESH_FIX_MS = 20_000L
        const val WALL_ENTRY_SECONDS = 1_000_000L
    }
}
