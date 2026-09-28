package io.customer.geofence

import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.GeofenceRegistrationIncarnation
import io.customer.sdk.communication.Event
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
        val coordinator = GeofenceDwellCoordinator(store, processor)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter("circle", enteredAtSeconds = 999L, beginsNewVisit = true)

        verify(exactly = 0) { store.saveDwellVisit(any()) }
    }

    @Test
    fun redundantEnterPreservesTheCurrentVisit() = runTest {
        val region = circle()
        val current = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "current-visit",
            enteredAtSeconds = 100L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns current
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter("circle", enteredAtSeconds = 800L, beginsNewVisit = false)

        verify(exactly = 0) { store.saveDwellVisit(any()) }
    }

    @Test
    fun longObservedVisitIsReportedOnExit() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        val visit = GeofenceDwellVisit(
            geofenceId = "circle",
            visitId = "long-visit",
            enteredAtSeconds = 100L,
            regionRevision = region.transitionRevision(),
            userStateGeneration = 7L,
            registrationElapsedMs = REGISTERED_AT_MS
        )
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns visit
        val coordinator = GeofenceDwellCoordinator(store, processor)

        val context = coordinator.onNativeExit(
            "circle",
            exitedAtSeconds = 100L + 7L * 86_400L,
            triggeringFixElapsedMs = FRESH_FIX_MS
        )

        context?.visitId shouldBeEqualTo "long-visit"
        context?.durationSeconds shouldBeEqualTo 7L * 86_400L
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
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onNativeDwell("circle", observedAtSeconds = 100L + 86_401L, triggeringFixElapsedMs = FRESH_FIX_MS)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

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
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val context = slot<GeofenceTransitionEmitter.VisitContext.Dwell>()
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onNativeDwell("circle", observedAtSeconds = 160L, triggeringFixElapsedMs = FRESH_FIX_MS)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 159L)
        coVerify(exactly = 0) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }

        coordinator.onInsideEvidence("polygon", observedAtSeconds = 165L)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 200L)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 165L)
        val emittedVisitId = visit?.visitId
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_061L)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_121L)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true)
        val firstVisitId = visit?.visitId
        coordinator.onExit("polygon", exitedAtSeconds = 200L, detectionSource = "location_evidence")

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter("polygon", enteredAtSeconds = 100L, beginsNewVisit = true)
        val originalVisitId = visit?.visitId
        coordinator.onInsideEvidence(
            "polygon",
            observedAtSeconds = 100L + 60L + 15L * 60L + 1L
        )

        coVerify(exactly = 1) { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) }
        visit?.visitId shouldBeEqualTo originalVisitId
        visit?.enteredAtSeconds shouldBeEqualTo 100L
        visit?.emitted shouldBeEqualTo true
    }

    @Test
    fun nativeDwellFromPreviousUserGeneration_isDropped() = runTest {
        every { store.userStateGeneration() } returns 8L
        val coordinator = GeofenceDwellCoordinator(store, processor)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

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
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } answers { visit }
        every { store.saveDwellVisit(any()) } answers {
            visit = firstArg()
            true
        }
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter("circle", enteredAtSeconds = 100L, beginsNewVisit = true)
        val visitId = visit?.visitId
        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 175L,
            triggeringFixElapsedMs = FRESH_FIX_MS
        )

        context?.visitId shouldBeEqualTo visitId
        context?.enteredAt shouldBeEqualTo 100L
        context?.durationSeconds shouldBeEqualTo 75L
        context?.detectionSource shouldBeEqualTo "native"
    }

    @Test
    fun exitWithoutObservedEntry_omitsVisitContext() = runTest {
        val region = circle().copy(transitionTypes = listOf(GeofenceTransitionType.EXIT))
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns liveRegistration(region)
        every { store.userStateGeneration() } returns 7L
        every { store.getDwellVisit("circle") } returns null
        val coordinator = GeofenceDwellCoordinator(store, processor)

        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 175L,
            triggeringFixElapsedMs = FRESH_FIX_MS
        )

        context shouldBeEqualTo null
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
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onNativeDwell("circle", observedAtSeconds = 1_000L, triggeringFixElapsedMs = FRESH_FIX_MS)
        val context = coordinator.onNativeExit("circle", exitedAtSeconds = 1_075L, triggeringFixElapsedMs = FRESH_FIX_MS)

        context shouldBeEqualTo null
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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 175L,
            triggeringFixElapsedMs = FRESH_FIX_MS
        )

        context shouldBeEqualTo null
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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        // Received well after the newer visit began, but triggered by a fix the replaced
        // registration evaluated. Receipt time alone would read this as a 500 s visit.
        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 1_500L,
            triggeringFixElapsedMs = REGISTERED_AT_MS - 1L
        )

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        val context = coordinator.onNativeExit(
            geofenceId = "circle",
            exitedAtSeconds = 1_000L,
            triggeringFixElapsedMs = FRESH_FIX_MS - 1L
        )

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onNativeExit("circle", exitedAtSeconds = 200L, triggeringFixElapsedMs = null).shouldBeNull()

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        // Containment already held the fence; the visit was wiped by a monitoring gap and the
        // re-registration's initial trigger reports the device inside.
        coordinator.onEnter("circle", enteredAtSeconds = 5_000L, beginsNewVisit = false)

        visit?.entryWasObserved shouldBeEqualTo false
        coordinator.onNativeExit("circle", exitedAtSeconds = 5_600L, triggeringFixElapsedMs = FRESH_FIX_MS)
            .shouldBeNull()
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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_000L)
        coordinator.onInsideEvidence("polygon", observedAtSeconds = 1_061L)

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
        coordinator.onExit("polygon", exitedAtSeconds = 1_200L, detectionSource = "location_evidence")
            .shouldBeNull()
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
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onNativeExit("circle", exitedAtSeconds = 5_000L, triggeringFixElapsedMs = FRESH_FIX_MS)
            .shouldBeNull()

        verify { store.removeDwellVisit("circle") }
    }

    @Test
    fun circleEnterWithoutALiveRegistration_startsNoVisit() = runTest {
        val region = circle()
        every { store.getCachedRegion("circle") } returns region
        every { store.getRegistrationIncarnation("circle") } returns null
        every { store.userStateGeneration() } returns 7L
        val coordinator = GeofenceDwellCoordinator(store, processor)

        coordinator.onEnter("circle", enteredAtSeconds = 100L, beginsNewVisit = true, entryFixElapsedMs = FRESH_FIX_MS)

        verify(exactly = 0) { store.saveDwellVisit(any()) }
    }

    private fun liveRegistration(region: GeofenceRegion) = GeofenceRegistrationIncarnation(
        geofenceId = region.id,
        regionRevision = region.transitionRevision(),
        registeredAtElapsedMs = REGISTERED_AT_MS
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
    }
}
