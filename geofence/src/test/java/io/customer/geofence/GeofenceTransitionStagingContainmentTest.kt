package io.customer.geofence

import android.location.Location
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.PolygonBootSessionProvider
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Physical containment and durable delivery can fail independently; these pin containment across an
 * ENTER then EXIT while the outbox is unavailable.
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
        every { cooldownFilter.suppressedForSeconds(any(), any(), any()) } returns null
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

        // The stage commits containment in the same write, so INSIDE with nothing deliverable yet.
        processor.process(GEOFENCE_ID, Event.GeofenceTransition.ENTER, timestampSeconds = 100L)

        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
        regionStore.getAllPendingTransitionEntries().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER)

        // The device really left; staying INSIDE would drop the next ENTER as redundant.
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
        // Replaying the older ENTER's containment would resurrect a visit already left.
        regionStore.getEnteredIds().shouldBeEmpty()
    }

    @Test
    fun recoverPendingTransitions_givenExitAlreadyQueuedWhileOlderEnterIsStillStaged_expectEnterFirst() = runTest {
        val processor = processor()
        every { outbox.appendAll(any()) } returns false
        processor.process(GEOFENCE_ID, Event.GeofenceTransition.ENTER, timestampSeconds = 100L)

        // Emission drains older staged attempts first, so the ENTER is queued ahead of the EXIT.
        every { outbox.appendAll(any()) } answers { callOriginal() }
        processor.process(GEOFENCE_ID, Event.GeofenceTransition.EXIT, timestampSeconds = 200L)
        outbox.loadAll().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.EXIT)

        // Re-appends both by key; appendAll replaces a matching row rather than duplicating it.
        processor.recoverPendingTransitions().shouldBeTrue()

        outbox.loadAll().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.EXIT)
        outbox.loadAll().map { it.timestamp } shouldBeEqualTo listOf(100L, 200L)
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

        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
    }

    @Test
    fun pipeline_givenExitDelayedBehindALaterDwell_expectVisitAndContainmentKept() = runTest {
        val dwellRegion = dwellRegion()
        armDwellRegion(dwellRegion)
        val coordinator = coordinator(regionStore)
        val pipeline = pipeline(regionStore, coordinator)

        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L, wallSeconds = 1_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 5_000L, wallSeconds = 1_070L))
        val visit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        visit.emitted.shouldBeTrue()

        // GMS decided this EXIT at 2 000, before the DWELL that still placed the device inside.
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 2_000L, wallSeconds = 1_080L))

        regionStore.getDwellVisit(GEOFENCE_ID) shouldBeEqualTo visit
        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
        outbox.loadAll().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.DWELL)

        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 90_000L, wallSeconds = 1_090L))

        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
        regionStore.getEnteredIds().shouldBeEmpty()
        outbox.loadAll().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.DWELL, Event.GeofenceTransition.EXIT)
    }

    @Test
    fun pipeline_givenEnterVisitWriteOvertakenByItsExit_expectNoVisitForTheEndedStay() = runTest {
        armDwellRegion(dwellRegion())
        val coordinator = spyk(coordinator(regionStore))
        val pipeline = pipeline(regionStore, coordinator)
        var exitRan = false
        // The ENTER is committed; its EXIT then runs to completion before the visit write.
        coEvery { coordinator.onEnter(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (!exitRan) {
                exitRan = true
                pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 2_000L, wallSeconds = 1_010L))
            }
            callOriginal()
        }

        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L, wallSeconds = 1_000L))

        exitRan.shouldBeTrue()
        regionStore.getEnteredIds().shouldBeEmpty()
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
    }

    @Test
    fun pipeline_givenEnterDecidedBeforeARecordedExit_expectNoEnterAndContainmentLeftOutside() = runTest {
        armDwellRegion(dwellRegion())
        val pipeline = pipeline(regionStore, coordinator(regionStore))
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L, wallSeconds = 1_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 5_000L, wallSeconds = 1_050L))

        // GMS decided this ENTER at 2 000, before the EXIT already handled.
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 2_000L, wallSeconds = 1_060L))

        outbox.loadAll().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.EXIT)
        regionStore.getEnteredIds().shouldBeEmpty()
        regionStore.getDwellVisit(GEOFENCE_ID).shouldBeNull()
    }

    @Test
    fun pipeline_givenOlderExitRunWhileANewerEnterIsHandled_expectExitDropped() = runTest {
        armDwellRegion(dwellRegion())
        val coordinator = spyk(coordinator(regionStore))
        val pipeline = pipeline(regionStore, coordinator)
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L, wallSeconds = 1_000L))
        var exitRan = false
        // The ENTER at 6 000 is committed; an EXIT GMS decided at 3 000 runs before its visit write.
        coEvery { coordinator.onEnter(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (!exitRan) {
                exitRan = true
                pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 3_000L, wallSeconds = 1_010L))
            }
            callOriginal()
        }

        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 6_000L, wallSeconds = 1_020L))

        exitRan.shouldBeTrue()
        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
        outbox.loadAll().map { it.transition } shouldBeEqualTo listOf(Event.GeofenceTransition.ENTER)
        regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
    }

    @Test
    fun pipeline_givenNewerEnterCommittedBetweenAnOlderExitsChecks_expectExitNotCommitted() = runTest {
        armDwellRegion(dwellRegion())
        val coordinator = spyk(coordinator(regionStore))
        val pipeline = pipeline(regionStore, coordinator)
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L, wallSeconds = 1_000L))
        var enterRan = false
        // The EXIT at 3 000 passed the visit check; an ENTER at 6 000 completes before its commit.
        coEvery { coordinator.onNativeExit(any(), any(), any(), any(), any()) } coAnswers {
            val current = callOriginal()
            if (!enterRan) {
                enterRan = true
                pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 6_000L, wallSeconds = 1_020L))
            }
            current
        }

        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 3_000L, wallSeconds = 1_010L))

        enterRan.shouldBeTrue()
        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
        outbox.loadAll().map { it.transition } shouldBeEqualTo listOf(Event.GeofenceTransition.ENTER)
    }

    @Test
    fun pipeline_givenRepeatEnterThenALateInBetweenExit_expectLaterDwellWithoutTheOriginalEntry() = runTest {
        // Both fences proved the device outside at registration, so ENTER@1000 is an observed entry.
        // OVERLAP_ID shares every callback except the late EXIT and must keep its own entry.
        val overlap = dwellRegion().copy(id = OVERLAP_ID)
        val fences = listOf(dwellRegion(), overlap)
        regionStore.saveCachedRegions(fences)
        regionStore.saveRegisteredIds(setOf(GEOFENCE_ID, OVERLAP_ID))
        regionStore.saveRoutableRegisteredIds(setOf(GEOFENCE_ID, OVERLAP_ID))
        regionStore.recordRegistrationIncarnations(
            fences,
            registeredAtElapsedMs = 500L,
            bootSessionId = "boot",
            outsideIds = setOf(GEOFENCE_ID, OVERLAP_ID)
        )
        val pipeline = pipeline(regionStore, coordinator(regionStore))
        val both = listOf(GEOFENCE_ID, OVERLAP_ID)
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L, ids = both))
        val visitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId

        // GMS placed the device inside again at 5 000; the EXIT it decided at 2 000 arrives late.
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 5_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 2_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 65_000L, ids = both))

        // The stay that began at 1 000 ended somewhere before 2 000, so neither its entry time nor
        // a duration measured from it describes the stay this DWELL reports.
        val dwell = outbox.loadAll().single { it.geofenceId == GEOFENCE_ID && it.transition == Event.GeofenceTransition.DWELL }
        dwell.transitionId shouldBeEqualTo visitId
        dwell.enteredAt.shouldBeNull()
        dwell.dwellDurationSeconds.shouldBeNull()
        val overlapDwell = outbox.loadAll().single { it.geofenceId == OVERLAP_ID && it.transition == Event.GeofenceTransition.DWELL }
        overlapDwell.enteredAt shouldBeEqualTo wallSecondsAt(1_000L)
        overlapDwell.dwellDurationSeconds shouldBeEqualTo 64L
        // Same visit, still inside, with only its entry withdrawn; a later EXIT reads that.
        val visit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        visit.visitId shouldBeEqualTo visitId
        visit.entryWasObserved shouldBeEqualTo false
        regionStore.getEnteredIds() shouldContainSame both.toSet()
        regionStore.getDwellVisit(OVERLAP_ID)?.entryWasObserved shouldBeEqualTo true

        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 90_000L, ids = both))

        // One DWELL per fence, and no EXIT for the superseded departure.
        outbox.loadAll().filter { it.geofenceId == GEOFENCE_ID }.map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.DWELL, Event.GeofenceTransition.EXIT)
        regionStore.getEnteredIds().shouldBeEmpty()
    }

    @Test
    fun pipeline_givenDuplicateEnterReadOutsideBeforeTheFirstWasAdmitted_expectTheFirstVisitKept() = runTest {
        assertSecondEnterKeepsTheFirstVisit(secondFixMs = 1_000L)
    }

    @Test
    fun pipeline_givenRepeatEnterReadOutsideBeforeTheFirstWasAdmitted_expectTheFirstVisitKept() = runTest {
        assertSecondEnterKeepsTheFirstVisit(secondFixMs = 1_300L)
    }

    @Test
    fun pipeline_givenRepeatEntersVisitWrittenBeforeTheFirsts_expectTheObservedEntryRecovered() = runTest {
        armDwellRegion(dwellRegion(), outsideProven = true)
        val coordinator = spyk(coordinator(regionStore))
        val pipeline = pipeline(regionStore, coordinator)
        var repeatRan = false
        // ENTER@1000 is committed; ENTER@1300 runs to completion before ENTER@1000's visit write.
        coEvery { coordinator.onEnter(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (!repeatRan) {
                repeatRan = true
                pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_300L))
            }
            callOriginal()
        }

        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))

        repeatRan.shouldBeTrue()
        val visit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        visit.entryWasObserved shouldBeEqualTo true
        visit.entryFixElapsedMs shouldBeEqualTo 1_000L
        visit.enteredAtSeconds shouldBeEqualTo wallSecondsAt(1_000L)
        visit.enteredAtElapsedMs shouldBeEqualTo 1_100L
        visit.lastInsideFixElapsedMs shouldBeEqualTo 1_300L
    }

    @Test
    fun pipeline_givenInBetweenExitBeforeTheFirstEntersVisitWrite_expectNoRecoveredEntry() = runTest {
        // Guard for the recovery above: an EXIT GMS decided at 1 200 shows the stay that began at
        // 1 000 ended, so the visit the repeat ENTER started must not inherit that entry.
        armDwellRegion(dwellRegion(), outsideProven = true)
        val coordinator = spyk(coordinator(regionStore))
        val pipeline = pipeline(regionStore, coordinator)
        var othersRan = false
        coEvery { coordinator.onEnter(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (!othersRan) {
                othersRan = true
                pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_300L))
                pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = 1_200L))
            }
            callOriginal()
        }

        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))

        othersRan.shouldBeTrue()
        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
        val visit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        visit.entryWasObserved shouldBeEqualTo false
        visit.entryFixElapsedMs shouldBeEqualTo 1_300L
    }

    /**
     * Both ENTERs read containment as outside before either was admitted. The second is held at its
     * transition step until the first has committed and written its visit.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.assertSecondEnterKeepsTheFirstVisit(secondFixMs: Long) {
        armDwellRegion(dwellRegion(), outsideProven = true)
        val processor = spyk(processor())
        val coordinator = coordinator(regionStore)
        val pipeline = pipeline(regionStore, coordinator, processor)
        val held = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { processor.process(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (++calls == 1) held.await()
            callOriginal()
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = secondFixMs))
        }

        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val first = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        first.entryWasObserved shouldBeEqualTo true
        held.complete(Unit)
        second.join()

        val visit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        visit.visitId shouldBeEqualTo first.visitId
        visit.entryWasObserved shouldBeEqualTo true
        visit.entryFixElapsedMs shouldBeEqualTo 1_000L
        visit.enteredAtElapsedMs shouldBeEqualTo first.enteredAtElapsedMs
        outbox.loadAll().map { it.transition } shouldBeEqualTo listOf(Event.GeofenceTransition.ENTER)
    }

    @Test
    fun pipeline_givenExitWithoutATriggeringFixThenALaterDwell_expectTheDwellWithoutEntryTiming() = runTest {
        armDwellRegion(dwellRegion(), outsideProven = true)
        val pipeline = pipeline(regionStore, coordinator(regionStore))
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val visitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId

        // The legacy EXIT is still delivered and moves containment outside, but its time is unknown.
        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = null, receivedAtElapsedMs = 30_100L))
        outbox.loadAll().map { it.transition } shouldBeEqualTo
            listOf(Event.GeofenceTransition.ENTER, Event.GeofenceTransition.EXIT)
        regionStore.getEnteredIds().shouldBeEmpty()
        // GMS later reports DWELL, so the device came back, but the ENTER for that was lost.
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 65_000L))

        // Still native evidence, but timing from ENTER@1000 would span the EXIT already sent.
        val dwell = outbox.loadAll().single { it.transition == Event.GeofenceTransition.DWELL }
        dwell.transitionId shouldBeEqualTo visitId
        dwell.enteredAt.shouldBeNull()
        dwell.dwellDurationSeconds.shouldBeNull()
    }

    @Test
    fun pipeline_givenDwellQueuedBeforeAnExitWithoutAFix_expectItUnchangedAndNotRepeated() = runTest {
        // Control: what is already queued is never rewritten, and a redelivery cannot add a second.
        armDwellRegion(dwellRegion(), outsideProven = true)
        val pipeline = pipeline(regionStore, coordinator(regionStore))
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 65_000L))
        val queued = outbox.loadAll().single { it.transition == Event.GeofenceTransition.DWELL }
        queued.enteredAt shouldBeEqualTo wallSecondsAt(1_000L)

        pipeline.handle(crossing(GeofenceCrossingTransition.EXIT, fixMs = null, receivedAtElapsedMs = 70_100L))
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 65_000L))

        outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL } shouldBeEqualTo listOf(queued)
    }

    @Test
    fun pipeline_givenKnownOutsideThenGmsDwellWithoutAnEnterSince_expectNoDwellAndNoEntryTiming() = runTest {
        armDwellRegion(dwellRegion(), outsideProven = true)
        val pipeline = pipeline(regionStore, coordinator(regionStore))
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val visitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId
        // A trusted movement fix at 9 000 cleared the kept circle's edge; GMS reported no EXIT.
        regionStore.raiseOutsideProof(setOf(GEOFENCE_ID), provenAtElapsedMs = 9_000L, bootSessionId = "boot")

        // GMS never reported leaving, so its loitering may still count from ENTER@1000, even for
        // a DWELL decided more than a threshold after the outside fix.
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 12_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 70_000L))

        outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL }.shouldBeEmpty()
        val visit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        visit.visitId shouldBeEqualTo visitId
        // A later EXIT must not date this stay from ENTER@1000 either.
        visit.entryWasObserved shouldBeEqualTo false
        regionStore.getEnteredIds() shouldContainSame setOf(GEOFENCE_ID)
    }

    @Test
    fun pipeline_givenKnownOutsideThenAnEnterWhoseVisitWriteWasLost_expectOneDwellForAFreshCandidate() = runTest {
        armDwellRegion(dwellRegion(), outsideProven = true)
        val coordinator = spyk(coordinator(regionStore))
        val pipeline = pipeline(regionStore, coordinator)
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val oldVisitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId
        regionStore.raiseOutsideProof(setOf(GEOFENCE_ID), provenAtElapsedMs = 9_000L, bootSessionId = "boot")
        // GMS re-entered at 20 000 and the ENTER committed, but the process died before its visit write.
        coEvery { coordinator.onEnter(any(), any(), any(), any(), any(), any(), any()) } returns Unit
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 20_000L))

        // GMS's loitering now counts from an ENTER after the outside fix.
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 80_000L))

        val dwell = outbox.loadAll().single { it.transition == Event.GeofenceTransition.DWELL }
        (dwell.transitionId == oldVisitId) shouldBeEqualTo false
        dwell.transitionId shouldBeEqualTo regionStore.getDwellVisit(GEOFENCE_ID)?.visitId
        dwell.enteredAt.shouldBeNull()
        dwell.dwellDurationSeconds.shouldBeNull()
    }

    @Test
    fun pipeline_givenEmittedVisitThenKnownOutside_expectNoRepeatButTheReEntrysOwnDwell() = runTest {
        armDwellRegion(dwellRegion(), outsideProven = true)
        val pipeline = pipeline(regionStore, coordinator(regionStore))
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 65_000L))
        val first = outbox.loadAll().single { it.transition == Event.GeofenceTransition.DWELL }
        regionStore.raiseOutsideProof(setOf(GEOFENCE_ID), provenAtElapsedMs = 70_000L, bootSessionId = "boot")

        // Neither GMS DWELL shows GMS noticed the departure, so neither describes the new stay.
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 140_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 65_000L))
        outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL } shouldBeEqualTo listOf(first)

        // GMS reports the re-entry; that stay is a new visit with its own observed entry.
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 150_000L))
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 210_000L))

        val dwells = outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL }
        dwells.size shouldBeEqualTo 2
        dwells.first() shouldBeEqualTo first
        (dwells.last().transitionId == first.transitionId) shouldBeEqualTo false
        dwells.last().enteredAt shouldBeEqualTo wallSecondsAt(150_000L)
        dwells.last().dwellDurationSeconds shouldBeEqualTo 60L
    }

    @Test
    fun pipeline_givenDelayedDwellDecidedBeforeAKnownOutside_expectNoDwell() = runTest {
        armDwellRegion(dwellRegion(), outsideProven = true)
        val pipeline = pipeline(regionStore, coordinator(regionStore))
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        regionStore.raiseOutsideProof(setOf(GEOFENCE_ID), provenAtElapsedMs = 9_000L, bootSessionId = "boot")

        // Decided at 5 000, before the device was seen outside, and delivered after: that stay ended.
        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 5_000L, receivedAtElapsedMs = 70_100L))

        outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL }.shouldBeEmpty()
    }

    @Test
    fun pipeline_givenOutsideProofOlderThanTheVisitsEntry_expectTheDwellWithItsEntryTiming() = runTest {
        // Control: proof from before the entry is what made the entry observed, not a break.
        armDwellRegion(dwellRegion(), outsideProven = true)
        regionStore.raiseOutsideProof(setOf(GEOFENCE_ID), provenAtElapsedMs = 900L, bootSessionId = "boot")
        val pipeline = pipeline(regionStore, coordinator(regionStore))
        pipeline.handle(crossing(GeofenceCrossingTransition.ENTER, fixMs = 1_000L))
        val visitId = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull().visitId

        pipeline.handle(crossing(GeofenceCrossingTransition.DWELL, fixMs = 65_000L))

        val dwell = outbox.loadAll().single { it.transition == Event.GeofenceTransition.DWELL }
        dwell.transitionId shouldBeEqualTo visitId
        dwell.enteredAt shouldBeEqualTo wallSecondsAt(1_000L)
        dwell.dwellDurationSeconds shouldBeEqualTo 64L
    }

    @Test
    fun dwellRetry_givenOutboxAcceptedButEmittedStateLost_expectFirstEvidenceRepeatedAfterRestart() = runTest {
        val dwellRegion = dwellRegion().copy(geosetIds = listOf("geoset-a", "geoset-b"))
        armDwellRegion(dwellRegion, outsideProven = true)
        val failingStore = spyk(regionStore)
        var failNextEmittedWrite = true
        every { failingStore.saveDwellVisit(any()) } answers {
            val visit = firstArg<io.customer.geofence.store.GeofenceDwellVisit>()
            if (visit.emitted && failNextEmittedWrite) {
                failNextEmittedWrite = false
                false
            } else {
                callOriginal()
            }
        }
        val scheduled = mutableListOf<PendingGeofenceDelivery>()
        coEvery { scheduler.schedule(capture(scheduled)) } returns Unit
        val processor = GeofenceBusinessTransitionProcessor(
            store = failingStore,
            secureUserStore = secureUserStore,
            transitionEmitter = GeofenceTransitionEmitter(cooldownFilter, outbox, scheduler, failingStore, logger),
            logger = logger
        )
        coordinator(failingStore, processor).onEnter(
            GEOFENCE_ID,
            enteredAtSeconds = 1_000L,
            beginsNewVisit = true,
            entryFixElapsedMs = 1_000L,
            enteredAtElapsedMs = 1_100L
        )
        coordinator(failingStore, processor)
            .onNativeDwell(GEOFENCE_ID, 1_065L, triggeringFixElapsedMs = 61_000L, observedAtElapsedMs = 66_100L)
        regionStore.getDwellVisit(GEOFENCE_ID)?.emitted shouldBeEqualTo false

        // A new process: the next attributed DWELL retries the emission that lost its state write.
        coordinator(failingStore, processor)
            .onNativeDwell(GEOFENCE_ID, 1_300L, triggeringFixElapsedMs = 300_000L, observedAtElapsedMs = 301_100L)

        val visit = regionStore.getDwellVisit(GEOFENCE_ID).shouldNotBeNull()
        visit.emitted.shouldBeTrue()
        val dwells = scheduled.filter { it.transition == Event.GeofenceTransition.DWELL }
        dwells.map { it.geosetId } shouldBeEqualTo listOf("geoset-a", "geoset-b", "geoset-a", "geoset-b")
        dwells.map { it.transitionId }.toSet() shouldBeEqualTo setOf(visit.visitId)
        dwells.map { listOf(it.timestamp, it.enteredAt, it.dwellDurationSeconds) }.toSet() shouldBeEqualTo
            setOf(listOf(1_065L, 1_000L, 65L))
        outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL }
            .map { it.timestamp to it.geosetId } shouldBeEqualTo listOf(1_065L to "geoset-a", 1_065L to "geoset-b")
    }

    private fun dwellRegion() = region().copy(dwellThresholdSeconds = 60)

    private fun armDwellRegion(region: GeofenceRegion, outsideProven: Boolean = false) {
        regionStore.saveCachedRegions(listOf(region))
        regionStore.recordRegistrationIncarnations(
            listOf(region),
            registeredAtElapsedMs = 500L,
            bootSessionId = "boot",
            outsideIds = if (outsideProven) setOf(region.id) else emptySet()
        )
    }

    private fun coordinator(
        store: GeofenceRegionStoreImpl,
        processor: GeofenceBusinessTransitionProcessor = processor()
    ) = GeofenceDwellCoordinator(store, processor, mockk<Clock>(relaxed = true), PolygonBootSessionProvider { "boot" })

    private fun pipeline(
        store: GeofenceRegionStoreImpl,
        coordinator: GeofenceDwellCoordinator,
        transitionProcessor: GeofenceBusinessTransitionProcessor = processor()
    ) = GeofenceCrossingPipeline(
        regionStore = store,
        services = mockk(relaxed = true),
        registrar = mockk(relaxed = true),
        transitionProcessor = transitionProcessor,
        dwellCoordinator = coordinator,
        polygonController = mockk(relaxed = true),
        logger = logger
    )

    /** Receipt wall time for a fix, on a clock that agrees with the boot clock and never steps. */
    private fun wallSecondsAt(fixMs: Long) = 1_000L + (fixMs + 100L) / 1_000L

    /** A null [fixMs] is a callback GMS delivered without a triggering location. */
    private fun crossing(
        transition: GeofenceCrossingTransition,
        fixMs: Long?,
        receivedAtElapsedMs: Long = requireNotNull(fixMs) + 100L,
        wallSeconds: Long = 1_000L + receivedAtElapsedMs / 1_000L,
        ids: List<String> = listOf(GEOFENCE_ID)
    ) = GeofenceCrossing(
        geofenceIds = ids,
        transition = transition,
        transitionName = transition.name,
        rawTransitionCode = 0,
        latitude = 0.0,
        longitude = 0.0,
        triggeringLocation = fixMs?.let {
            Location("gps").apply {
                latitude = 0.0
                longitude = 0.0
                accuracy = 10f
                elapsedRealtimeNanos = it * 1_000_000L
            }
        },
        receivedAtSeconds = wallSeconds,
        receivedAtElapsedMs = receivedAtElapsedMs
    )

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
        const val OVERLAP_ID = "biz-2"
    }
}
