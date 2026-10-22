package io.customer.geofence.polygon

import android.content.Context
import android.location.Location
import android.os.SystemClock
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.DwellDiagnosticEvent
import io.customer.geofence.DwellDiagnosticJournal
import io.customer.geofence.DwellDiagnosticRecord
import io.customer.geofence.DwellDiagnosticRecorder
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceCooldownFilter
import io.customer.geofence.GeofenceDwellCoordinator
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceRegistrationGate
import io.customer.geofence.GeofenceTransitionEmitter
import io.customer.geofence.PolygonFreshFixSkip
import io.customer.geofence.store.GeofenceDwellReservation
import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.transitionRevision
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * A parked device's wake can deliver a fix that keeps a polygon committed INSIDE without showing
 * the stay, after its visit has passed the dwell threshold. Real controller, engine, coordinator,
 * processor, emitter, store and outbox; only Play services, the scheduler and the approach monitor
 * are faked.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonDwellPrecisionTest : RobolectricTest() {

    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private val cooldownFilter: GeofenceCooldownFilter = mockk(relaxed = true)

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var outbox: PendingDeliveryStore<PendingGeofenceDelivery>
    private lateinit var wakeRecords: PolygonDwellWakeRecordStore
    private val registrationGate = GeofenceRegistrationGate()

    /** What GMS holds per id: the DWELL loitering delay, or null for the plain ENTER|EXIT circle. */
    private val gmsLoiteringMs = mutableMapOf<String, Long?>()

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        outbox = PendingDeliveryStore(
            context = applicationMock,
            fileName = "cio_test_polygon_dwell_precision_outbox.json",
            elementSerializer = PendingGeofenceDelivery.serializer(),
            logger = mockk(relaxed = true)
        ).also { it.removeAll() }
        wakeRecords = PolygonDwellWakeRecordStore(
            applicationMock.getSharedPreferences(PolygonDwellWakeRecordStore.PREFS_NAME, Context.MODE_PRIVATE)
        ).also { it.clear() }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))

        every { secureUserStore.getUserId() } returns USER_ID
        // Both clocks advance with the shadow clock, so wall stamps keep agreeing with the boot clock.
        every { clock.elapsedRealtime() } answers { SystemClock.elapsedRealtime() }
        every { clock.currentTimeMillis() } answers { wallNowMs() }
        every { clock.currentTimeSeconds() } answers { wallNowMs() / MILLIS_PER_SECOND }
        every { cooldownFilter.suppressedForSeconds(any(), any(), any()) } returns null
        coEvery { manager.replaceMovementTrigger(any()) } returns Result.success(Unit)
        // A fake of what GMS holds, so tests read registration state rather than call order.
        coEvery { manager.armPolygonDwellWake(any(), any()) } answers {
            gmsLoiteringMs[firstArg<GeofenceRegion>().id] = secondArg<Long>()
            Result.success(Unit)
        }
        coEvery { manager.disarmPolygonDwellWake(any()) } answers {
            gmsLoiteringMs[firstArg<GeofenceRegion>().id] = null
            Result.success(Unit)
        }
        coEvery { manager.removeGeofencesByIds(any()) } answers {
            firstArg<List<String>>().forEach { gmsLoiteringMs.remove(it) }
            Result.success(Unit)
        }

        store.beginUserSession(USER_ID)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))
        // Sync registered the venue's circle normally.
        gmsLoiteringMs[VENUE_ID] = null
    }

    @Test
    fun activate_givenASpurredVenueAndACoarseFixWhoseWholeCircleIsInsideItsBody_expectTheDwellWithoutAPreciseFix() = runTest {
        // Before the controller exists, so it only ever sees the spurred ring.
        store.saveCachedRegions(listOf(spurredVenueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)

        arriveClearly(controller)
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()

        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        // ~110 m deep at 100 m of error: clear of the ring by ~10 m, while the spur drags the
        // ring's scale (~63 m) under the accuracy.
        val coarse = coarseFixInsideTheSpurredBody(SystemClock.elapsedRealtimeNanos() - 1_000_000_000L)
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarse,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        val dwells = dwells()
        (dwells.map { it.transitionId } to freshFix.priorities.toList()) shouldBeEqualTo
            (listOf(visit.visitId) to emptyList<PolygonFixPriority>())
        dwells.single().timestamp shouldBeEqualTo coarse.time / MILLIS_PER_SECOND
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().emitted shouldBeEqualTo true
    }

    @Test
    fun activate_givenADueVisitAndAFixInsideByLessThanItsAccuracy_expectOnePreciseFixQualifiesTheDwell() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)

        // A clear arrival: ~52 m inside the ring at 8 m of error decides alone, so nothing is asked.
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos() - 1_000_000_000L),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        store.getEnteredIds() shouldContain VENUE_ID
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        freshFix.priorities.shouldBeEmpty()

        // Still parked, now past the 60 s threshold on both clocks.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        val precise = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        freshFix.answer = precise

        // What a parked wake typically delivers: judged, under the arrival ceiling, but too coarse to
        // place the whole fix inside the ring. It keeps the committed state and proves no stay.
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = marginalFixInsideTheVenue(SystemClock.elapsedRealtimeNanos() - 5_000_000_000L),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.priorities shouldBeEqualTo listOf(PolygonFixPriority.HIGH_ACCURACY)
        val dwells = dwells()
        dwells.map { it.transitionId } shouldBeEqualTo listOf(visit.visitId)
        dwells.single().visitId shouldBeEqualTo visit.visitId
        // Dated by the fix that showed the stay, not the one that could not.
        dwells.single().timestamp shouldBeEqualTo precise.time / MILLIS_PER_SECOND
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().emitted shouldBeEqualTo true
    }

    @Test
    fun activate_givenAMarginalFixBeforeTheThreshold_expectNoRequestUntilTheVisitIsDue() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        wakeWithMarginalFix(controller)

        // Not yet due: nothing a precise fix showed could emit, so none is asked for.
        freshFix.priorities.shouldBeEmpty()
        dwells().shouldBeEmpty()

        ShadowSystemClock.advanceBy(Duration.ofSeconds(40))
        freshFix.answer = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        wakeWithMarginalFix(controller)

        // The early wake left no memo to suppress the due one.
        freshFix.priorities shouldBeEqualTo listOf(PolygonFixPriority.HIGH_ACCURACY)
        dwells().size shouldBeEqualTo 1
    }

    @Test
    fun activate_givenTheDwellAlreadyEmitted_expectLaterMarginalWakesAskForNothing() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        freshFix.answer = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        wakeWithMarginalFix(controller)
        dwells().size shouldBeEqualTo 1

        // Past the cooldown and the batch reuse window, still parked and still coarse.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        wakeWithMarginalFix(controller)

        freshFix.priorities shouldBeEqualTo listOf(PolygonFixPriority.HIGH_ACCURACY)
        dwells().size shouldBeEqualTo 1
    }

    @Test
    fun activate_givenAPreciseFixThatIsStillMarginal_expectNoRepeatFromTheSameSpotUntilTheRetryWindow() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        freshFix.answer = marginalFixInsideTheVenue(SystemClock.elapsedRealtimeNanos())
        wakeWithMarginalFix(controller)

        freshFix.priorities shouldBeEqualTo listOf(PolygonFixPriority.HIGH_ACCURACY)
        dwells().shouldBeEmpty()
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().emitted shouldBeEqualTo false

        // Past the cooldown, same spot: the futile answer is remembered, not asked again.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        wakeWithMarginalFix(controller)
        freshFix.priorities.size shouldBeEqualTo 1
        verify { logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION) }

        // A stationary device can still get a precise fix later, so the retry window lets one through.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        freshFix.answer = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        wakeWithMarginalFix(controller)

        freshFix.priorities.size shouldBeEqualTo 2
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        dwells().map { it.visitId } shouldBeEqualTo listOf(visit.visitId)
        visit.emitted shouldBeEqualTo true
    }

    @Test
    fun activate_givenNoPreciseFixArrives_expectNoDwellAndNoRepeatFromTheSameSpot() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        wakeWithMarginalFix(controller)

        freshFix.priorities shouldBeEqualTo listOf(PolygonFixPriority.HIGH_ACCURACY)
        dwells().shouldBeEmpty()

        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        wakeWithMarginalFix(controller)

        freshFix.priorities.size shouldBeEqualTo 1
        dwells().shouldBeEmpty()
    }

    @Test
    fun activate_givenTheRequestEchoesTheWakeFix_expectNoRepeatFromTheSameSpotUntilTheRetryWindow() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))

        // GMS can answer a max-age-0 request with the wake's own fix, stamp and all. It is not newer
        // than the fix the venue last judged, so it shows nothing the wake did not.
        val wake = marginalFixInsideTheVenue(SystemClock.elapsedRealtimeNanos() - 5_000_000_000L)
        freshFix.answer = wake
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = wake,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.priorities shouldBeEqualTo listOf(PolygonFixPriority.HIGH_ACCURACY)
        dwells().shouldBeEmpty()
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().emitted shouldBeEqualTo false

        // Past the cooldown, same spot: the echo is remembered as futile and not asked again.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        wakeWithMarginalFix(controller)
        freshFix.priorities.size shouldBeEqualTo 1
        verify { logger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION) }

        // The retry window still lets one request through, and a precise answer then shows the stay.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        freshFix.answer = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        wakeWithMarginalFix(controller)

        freshFix.priorities.size shouldBeEqualTo 2
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        dwells().map { it.visitId } shouldBeEqualTo listOf(visit.visitId)
        visit.emitted shouldBeEqualTo true
    }

    @Test
    fun awaitsDwellProof_expectOnlyACurrentUnemittedUnreservedVisitPastItsThreshold() = runTest {
        val coordinator = GeofenceDwellCoordinator(store, processor(), clock) { BOOT_SESSION_ID }
        val generation = store.userStateGeneration()
        val startedAt = SystemClock.elapsedRealtime()
        val due = startedAt + 60_000L
        val visit = GeofenceDwellVisit(
            geofenceId = VENUE_ID,
            visitId = "visit-1",
            enteredAtSeconds = wallNowMs() / MILLIS_PER_SECOND,
            regionRevision = venueRegion().transitionRevision(),
            userStateGeneration = generation,
            bootSessionId = BOOT_SESSION_ID,
            enteredAtElapsedMs = startedAt
        )
        var visitSequence = 0
        suspend fun awaits(stored: GeofenceDwellVisit, atElapsedMs: Long = due, expected: Long = generation): Boolean {
            store.removeDwellVisit(VENUE_ID)
            // Removing a visit marks its id ended, so each scenario must own a new visit.
            store.saveDwellVisit(stored.copy(visitId = "query-${visitSequence++}")) shouldBeEqualTo true
            return coordinator.awaitsDwellProof(VENUE_ID, atElapsedMs, expected)
        }

        awaits(visit) shouldBeEqualTo true
        awaits(visit, atElapsedMs = due - 1L) shouldBeEqualTo false
        awaits(visit.copy(emitted = true)) shouldBeEqualTo false
        awaits(
            visit.copy(
                dwellReservation = GeofenceDwellReservation(
                    timestampSeconds = 0L,
                    enteredAt = null,
                    thresholdSeconds = 60,
                    durationSeconds = null,
                    detectionSource = "location_evidence"
                )
            )
        ) shouldBeEqualTo false
        // No duration can be measured across boots or from a visit that predates boot-clock stamps.
        awaits(visit.copy(bootSessionId = "another-boot")) shouldBeEqualTo false
        awaits(visit.copy(enteredAtElapsedMs = null)) shouldBeEqualTo false
        // Edit after saving: the store correctly refuses a stale revision prepared after the edit.
        awaits(visit) shouldBeEqualTo true
        store.saveCachedRegions(listOf(venueRegion().copy(radius = 110f)))
        coordinator.awaitsDwellProof(VENUE_ID, due, generation) shouldBeEqualTo false
        store.saveCachedRegions(listOf(venueRegion()))
        awaits(visit, expected = generation + 1) shouldBeEqualTo false
    }

    @Test
    fun onDwellWake_givenAnArmedDueVisit_expectOnlyAPreciseFixQualifiesAndTheCircleIsRestored() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix, wakeOwner(enabled = true))
        arriveClearly(controller)
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()

        // The engine's entry cue arms once, with the delay counted from the original entry, not the arm.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        advanceUntilIdle()
        val armedDelayMs = venueRegion().dwellThresholdSeconds * MILLIS_PER_SECOND -
            (SystemClock.elapsedRealtime() - visit.enteredAtElapsedMs.shouldNotBeNull()) +
            PolygonDwellWakeOwner.DEFAULT_GRACE_MS
        gmsLoiteringMs shouldBeEqualTo mapOf<String, Long?>(VENUE_ID to armedDelayMs)
        armedVisitIds() shouldBeEqualTo mapOf(VENUE_ID to visit.visitId)

        // Due, but the wake's fix cannot show the stay and no precise fix comes. A wake is not proof:
        // no DWELL, and the visit keeps its wake and record.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(50))
        dwellWake(controller)
        advanceUntilIdle()
        freshFix.priorities shouldBeEqualTo listOf(PolygonFixPriority.HIGH_ACCURACY)
        dwells().shouldBeEmpty()
        gmsLoiteringMs shouldBeEqualTo mapOf<String, Long?>(VENUE_ID to armedDelayMs)
        armedVisitIds() shouldBeEqualTo mapOf(VENUE_ID to visit.visitId)

        // Same spot past the cooldown: the futile memo refuses an ordinary callback...
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        val precise = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        freshFix.answer = precise
        wakeWithMarginalFix(controller)
        freshFix.priorities.size shouldBeEqualTo 1
        // ...but the armed wake gets its one precise request, which shows the stay.
        dwellWake(controller)
        advanceUntilIdle()

        freshFix.priorities.size shouldBeEqualTo 2
        val dwell = dwells().single()
        dwell.visitId shouldBeEqualTo visit.visitId
        dwell.timestamp shouldBeEqualTo precise.time / MILLIS_PER_SECOND
        // Qualified by the precise fix, not by GMS's loitering.
        dwell.detectionSource shouldBeEqualTo "location_evidence"
        // Disarmed in place to the plain circle, not removed, and the record is gone.
        gmsLoiteringMs shouldBeEqualTo mapOf<String, Long?>(VENUE_ID to null)
        armedVisitIds() shouldBeEqualTo emptyMap()

        // A duplicate delivery finds no record: no request, no second DWELL, no re-arm.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        dwellWake(controller)
        advanceUntilIdle()
        freshFix.priorities.size shouldBeEqualTo 2
        dwells().size shouldBeEqualTo 1
        coVerify(exactly = 1) { manager.armPolygonDwellWake(any(), any()) }
    }

    @Test
    fun onDwellWake_givenTheExperimentOff_expectNoArmAndAPreciseWakeDecidesNothing() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix, wakeOwner(enabled = false))
        arriveClearly(controller)
        advanceUntilIdle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        freshFix.answer = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        val preciseWake = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos() - 1_000_000_000L)

        dwellWake(controller, preciseWake)
        advanceUntilIdle()

        freshFix.priorities.shouldBeEmpty()
        dwells().shouldBeEmpty()
        armedVisitIds() shouldBeEqualTo emptyMap()
        gmsLoiteringMs shouldBeEqualTo mapOf<String, Long?>(VENUE_ID to null)
        coVerify(exactly = 0) { manager.armPolygonDwellWake(any(), any()) }
        coVerify(exactly = 0) { manager.disarmPolygonDwellWake(any()) }

        // Control: the same fix through the ordinary callback qualifies, so only the off switch kept the
        // wake inert, and the wake left no coarse dedup state to refuse it.
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = preciseWake,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        val visitId = store.getDwellVisit(VENUE_ID).shouldNotBeNull().visitId
        dwells().map { it.visitId } shouldBeEqualTo listOf(visitId)
    }

    @Test
    fun onDwellWake_givenAPreciseDeliveredFix_expectDwellWithoutAnotherFixRequest() = runTest {
        val fresh = RecordingFreshFix()
        val controller = controller(fresh, wakeOwner(enabled = true))
        arriveClearly(controller)
        advanceUntilIdle()
        val visitId = store.getDwellVisit(VENUE_ID).shouldNotBeNull().visitId
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))

        dwellWake(controller, fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()))
        advanceUntilIdle()

        dwells().map { it.visitId } shouldBeEqualTo listOf(visitId)
        fresh.priorities.shouldBeEmpty()
        gmsLoiteringMs shouldBeEqualTo mapOf<String, Long?>(VENUE_ID to null)
        armedVisitIds() shouldBeEqualTo emptyMap()
    }

    @Test
    fun onDwellWake_givenOptOutAfterArming_expectCleanupWithoutRequestingOrEmitting() = runTest {
        val fresh = RecordingFreshFix()
        arriveClearly(controller(fresh, wakeOwner(enabled = true)))
        advanceUntilIdle()
        armedVisitIds().keys shouldBeEqualTo setOf(VENUE_ID)

        dwellWake(controller(fresh, wakeOwner(enabled = false)))
        advanceUntilIdle()

        fresh.priorities.shouldBeEmpty()
        dwells().shouldBeEmpty()
        gmsLoiteringMs shouldBeEqualTo mapOf<String, Long?>(VENUE_ID to null)
        armedVisitIds() shouldBeEqualTo emptyMap()
    }

    @Test
    fun processFix_givenDwellReservedButNotStaged_expectEngineReleasesAcquisitionBeforeDelivery() = runTest {
        val fresh = RecordingFreshFix()
        val owner = wakeOwner(enabled = true)
        arriveClearly(controller(fresh, owner))
        advanceUntilIdle()
        val visitId = store.getDwellVisit(VENUE_ID).shouldNotBeNull().visitId
        val blocked = mockk<GeofenceBusinessTransitionProcessor>(relaxed = true)
        coEvery { blocked.recoverPendingTransitions() } returns true
        coEvery { blocked.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSIST_FAILED
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))

        // An ordinary precise callback must release acquisition at reservation, without a native wake's settle.
        controller(fresh, owner, blocked).activate(
            VENUE_ID,
            fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration()
        )
        advanceUntilIdle()

        val reserved = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        reserved.visitId shouldBeEqualTo visitId
        reserved.dwellReservation.shouldNotBeNull()
        reserved.emitted shouldBeEqualTo false
        dwells().shouldBeEmpty()
        fresh.priorities.shouldBeEmpty()
        gmsLoiteringMs shouldBeEqualTo mapOf<String, Long?>(VENUE_ID to null)
        armedVisitIds() shouldBeEqualTo emptyMap()
    }

    @Test
    fun diagnostics_givenRepeatedInsideEvidenceThenDueVisit_expectOneThresholdReasonAndDurablyQueuedEmission() = runTest {
        val (journal, recorder) = diagnosticHistory()
        val controller = controller(RecordingFreshFix(), diagnosticRecorder = recorder)
        arriveClearly(controller)
        advanceUntilIdle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        controller.activate(VENUE_ID, fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()), store.userStateGeneration())
        advanceUntilIdle()
        val before = journal.snapshot().orEmpty().filter { it.event == DwellDiagnosticEvent.DWELL_REJECTED }
        before.map { it.reason } shouldBeEqualTo listOf(io.customer.geofence.DwellDiagnosticReason.BELOW_THRESHOLD)
        val expectedDue = store.getDwellVisit(VENUE_ID).shouldNotBeNull().enteredAtElapsedMs!! + 60_000L
        before.single().dueElapsedMs shouldBeEqualTo expectedDue

        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        controller.activate(VENUE_ID, fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()), store.userStateGeneration())
        advanceUntilIdle()
        dwells().size shouldBeEqualTo 1
        val emitted = journal.snapshot().orEmpty().filter { it.event == DwellDiagnosticEvent.DWELL_EMITTED }
        emitted.size shouldBeEqualTo 1
        emitted.single().fenceId shouldBeEqualTo VENUE_ID
        emitted.single().dueElapsedMs shouldBeEqualTo expectedDue
    }

    @Test
    fun diagnostics_givenNoOutboxOrStagingAfterReservation_expectNoClaimOfEmission() = runTest {
        val (journal, recorder) = diagnosticHistory()
        arriveClearly(controller(RecordingFreshFix()))
        val blocked = mockk<GeofenceBusinessTransitionProcessor>(relaxed = true)
        coEvery { blocked.recoverPendingTransitions() } returns true
        coEvery { blocked.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSIST_FAILED
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        controller(RecordingFreshFix(), processorOverride = blocked, diagnosticRecorder = recorder).activate(
            VENUE_ID,
            fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration()
        )
        advanceUntilIdle()
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().dwellReservation.shouldNotBeNull()
        dwells().shouldBeEmpty()
        journal.snapshot().orEmpty().filter { it.event == DwellDiagnosticEvent.DWELL_EMITTED }.shouldBeEmpty()
    }

    @Test
    fun onDwellWake_givenTeardownAndReactivationWhileEngineRecoversOutbox_expectRetiredPassDropped() = runTest {
        val owner = wakeOwner(enabled = true)
        arriveClearly(controller(RecordingFreshFix(), owner))
        advanceUntilIdle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        val enteredRecovery = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val blocked = mockk<GeofenceBusinessTransitionProcessor>(relaxed = true)
        coEvery { blocked.recoverPendingTransitions() } coAnswers {
            enteredRecovery.complete(Unit)
            releaseRecovery.await()
            true
        }
        coEvery { blocked.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        val controller = controller(RecordingFreshFix(), owner, blocked)
        val precise = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        val wake = launch {
            controller.onDwellWake(VENUE_ID, precise, store.userStateGeneration())
        }
        enteredRecovery.await()
        controller.stopAll()
        controller.activate(VENUE_ID, expectedUserStateGeneration = store.userStateGeneration()) shouldBeEqualTo true
        releaseRecovery.complete(Unit)
        wake.join()
        advanceUntilIdle()

        store.getDwellVisit(VENUE_ID).shouldNotBeNull().emitted shouldBeEqualTo false
        // The same evidence remains usable by a current ordinary callback.
        controller.activate(VENUE_ID, precise, store.userStateGeneration())
        advanceUntilIdle()
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().emitted shouldBeEqualTo true
    }

    @Test
    fun onDwellWake_givenTeardownAndReactivationWhileAwaitingFix_expectOldAnswerDropped() = runTest {
        val fresh = HoldingFreshFix()
        val controller = controller(fresh, wakeOwner(enabled = true))
        arriveClearly(controller)
        advanceUntilIdle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        val oldWake = launch { dwellWake(controller) }
        fresh.requested.await()

        controller.stopAll()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        controller.activate(VENUE_ID, expectedUserStateGeneration = store.userStateGeneration()) shouldBeEqualTo true
        val precise = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        fresh.answer.complete(precise)
        oldWake.join()
        advanceUntilIdle()

        dwells().shouldBeEmpty()
        store.getActivePolygonIds() shouldContain VENUE_ID
        // The current session can use the same fix through its own callback; only the retired wake is refused.
        controller.activate(VENUE_ID, precise, store.userStateGeneration())
        advanceUntilIdle()
        dwells().size shouldBeEqualTo 1
    }

    @Test
    fun beginUserSession_givenIdentityChanged_expectSynchronousDiagnosticRetirementAndSameUserHistoryKept() = runTest {
        val (journal, recorder) = diagnosticHistory()
        val controller = controller(RecordingFreshFix(), diagnosticRecorder = recorder)
        recorder.record(DwellDiagnosticRecord(DwellDiagnosticEvent.DEADLINE_SCHEDULED, SystemClock.elapsedRealtime(), "old"))
        advanceUntilIdle()
        val token = recorder.currentToken()

        controller.beginUserSessionForCurrentUser()
        recorder.currentToken() shouldBeEqualTo token
        advanceUntilIdle()
        journal.snapshot()?.map { it.fenceId } shouldBeEqualTo listOf("old")

        every { secureUserStore.getUserId() } returns "user-2"
        controller.beginUserSessionForCurrentUser()
        recorder.currentToken() shouldBeEqualTo token + 1L
        recorder.record(DwellDiagnosticRecord(DwellDiagnosticEvent.FIX_RECEIVED, SystemClock.elapsedRealtime(), "old"), token)
        recorder.record(DwellDiagnosticRecord(DwellDiagnosticEvent.DEADLINE_SCHEDULED, SystemClock.elapsedRealtime(), "new"))
        advanceUntilIdle()

        store.activeUserSessionId() shouldBeEqualTo "user-2"
        journal.snapshot()?.map { it.fenceId } shouldBeEqualTo listOf("new")
    }

    @Test
    fun diagnostics_givenStopsAndSupersededReset_expectHistoryRetainedUntilSessionEnds() = runTest {
        val (journal, recorder) = diagnosticHistory()
        val controller = controller(RecordingFreshFix(), diagnosticRecorder = recorder)
        val oldGeneration = store.userStateGeneration()
        recorder.record(DwellDiagnosticRecord(DwellDiagnosticEvent.DEADLINE_SCHEDULED, SystemClock.elapsedRealtime(), "before-stop"))
        advanceUntilIdle()

        controller.stopAll()
        controller.invalidateOsRegistrationState()
        advanceUntilIdle()
        journal.snapshot()?.map { it.fenceId } shouldBeEqualTo listOf("before-stop")

        every { secureUserStore.getUserId() } returns "user-2"
        controller.beginUserSessionForCurrentUser()
        recorder.record(DwellDiagnosticRecord(DwellDiagnosticEvent.DEADLINE_SCHEDULED, SystemClock.elapsedRealtime(), "new"))
        advanceUntilIdle()
        controller.completeUserReset(oldGeneration, osRegistrationsCleared = true)
        advanceUntilIdle()
        journal.snapshot()?.map { it.fenceId } shouldBeEqualTo listOf("new")

        controller.clearUserSessionRetainingOsRegistrations()
        advanceUntilIdle()
        journal.snapshot() shouldBeEqualTo emptyList()
    }

    @Test
    fun diagnostics_givenNativeArmAndConfirmedStop_expectOriginalDueTimeAndCleanupRecorded() = runTest {
        val (journal, recorder) = diagnosticHistory()
        val controller = controller(RecordingFreshFix(), wakeOwner(true, recorder), diagnosticRecorder = recorder)
        arriveClearly(controller)
        advanceUntilIdle()
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        val scheduled = journal.snapshot().shouldNotBeNull()
        scheduled.filter {
            it.event in setOf(DwellDiagnosticEvent.ARM_REQUESTED, DwellDiagnosticEvent.ARM_SUCCEEDED, DwellDiagnosticEvent.DEADLINE_SCHEDULED)
        }.map { it.event } shouldBeEqualTo listOf(
            DwellDiagnosticEvent.ARM_REQUESTED,
            DwellDiagnosticEvent.ARM_SUCCEEDED,
            DwellDiagnosticEvent.DEADLINE_SCHEDULED
        )
        scheduled.single { it.event == DwellDiagnosticEvent.DEADLINE_SCHEDULED }.dueElapsedMs shouldBeEqualTo
            visit.enteredAtElapsedMs.shouldNotBeNull() + venueRegion().dwellThresholdSeconds * MILLIS_PER_SECOND +
            PolygonDwellWakeOwner.DEFAULT_GRACE_MS

        controller.stopAll()
        advanceUntilIdle()

        journal.snapshot().shouldNotBeNull().filter {
            it.event in setOf(DwellDiagnosticEvent.DISARM_REQUESTED, DwellDiagnosticEvent.DISARM_SUCCEEDED, DwellDiagnosticEvent.DEADLINE_CANCELLED)
        }.map { it.event } shouldBeEqualTo listOf(
            DwellDiagnosticEvent.DISARM_REQUESTED,
            DwellDiagnosticEvent.DISARM_SUCCEEDED,
            DwellDiagnosticEvent.DEADLINE_CANCELLED
        )
    }

    private fun TestScope.diagnosticHistory(): Pair<DwellDiagnosticJournal, DwellDiagnosticRecorder> {
        val prefs = applicationMock.getSharedPreferences("cio_test_dwell_controller_history", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val journal = DwellDiagnosticJournal(lazyOf(prefs), { true }, StandardTestDispatcher(testScheduler))
        return journal to DwellDiagnosticRecorder(journal, { true }, this)
    }

    /** The real owner on this test's scheduler, writing real records. */
    private fun TestScope.wakeOwner(enabled: Boolean, diagnosticRecorder: DwellDiagnosticRecorder? = null) = PolygonDwellWakeOwner(
        store = store,
        registrar = manager,
        records = wakeRecords,
        gate = registrationGate,
        secureUserStore = secureUserStore,
        bootSessionProvider = { BOOT_SESSION_ID },
        enabled = { enabled },
        elapsedRealtimeMs = { SystemClock.elapsedRealtime() },
        scope = this,
        ioContext = StandardTestDispatcher(testScheduler),
        diagnostics = diagnosticRecorder
    )

    private fun armedVisitIds(): Map<String, String> =
        (wakeRecords.read() as PolygonDwellWakeRecords.Known).byPolygonId.mapValues { it.value.visitId }

    /** A native DWELL on the venue's circle; by default with a parked fix that cannot show the stay. */
    private suspend fun dwellWake(
        controller: PolygonGeofenceServiceController,
        fix: Location = marginalFixInsideTheVenue(SystemClock.elapsedRealtimeNanos() - 5_000_000_000L)
    ) {
        controller.onDwellWake(
            polygonId = VENUE_ID,
            triggeringLocation = fix,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = venueRegion().transitionRevision()
        )
    }

    /** A clear arrival that decides alone, so the visit starts without any request. */
    private suspend fun arriveClearly(controller: PolygonGeofenceServiceController) {
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos() - 1_000_000_000L),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        store.getEnteredIds() shouldContain VENUE_ID
        store.getDwellVisit(VENUE_ID).shouldNotBeNull()
    }

    /** A parked wake whose fix keeps the polygon INSIDE without showing the stay. */
    private suspend fun wakeWithMarginalFix(controller: PolygonGeofenceServiceController) {
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = marginalFixInsideTheVenue(SystemClock.elapsedRealtimeNanos() - 5_000_000_000L),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
    }

    private fun processor() = GeofenceBusinessTransitionProcessor(
        store,
        secureUserStore,
        GeofenceTransitionEmitter(cooldownFilter, outbox, mockk(relaxed = true), store, logger),
        logger
    )

    private fun dwells() = outbox.loadAll().filter { it.transition == Event.GeofenceTransition.DWELL }

    private fun wallNowMs() = WALL_CLOCK_AT_BOOT_MS + SystemClock.elapsedRealtime()

    private fun controller(
        freshFixSource: PolygonFreshFixSource,
        owner: PolygonDwellWakeOwner? = null,
        processorOverride: GeofenceBusinessTransitionProcessor? = null,
        diagnosticRecorder: DwellDiagnosticRecorder? = null
    ): PolygonGeofenceServiceController {
        val processor = processorOverride ?: GeofenceBusinessTransitionProcessor(
            store,
            secureUserStore,
            GeofenceTransitionEmitter(cooldownFilter, outbox, mockk(relaxed = true), store, logger),
            logger
        )
        return PolygonGeofenceServiceController(
            context = applicationMock,
            store = store,
            engine = PolygonLocationEngine(
                store = store,
                transitionProcessor = processor,
                clock = clock,
                logger = logger,
                dwellCoordinator = GeofenceDwellCoordinator(
                    store,
                    processor,
                    clock,
                    diagnosticRecorder = diagnosticRecorder
                ) { BOOT_SESSION_ID },
                dwellWakeOwner = owner
            ),
            approachMonitor = approachMonitor,
            manager = manager,
            secureUserStore = secureUserStore,
            freshFixSource = freshFixSource,
            recheckScheduler = NoopRecheckScheduler,
            passiveMonitor = NoopPassiveMonitor,
            bootSessionProvider = { BOOT_SESSION_ID },
            logger = logger,
            dwellWakeOwner = owner,
            diagnosticRecorder = diagnosticRecorder
        )
    }

    private class HoldingFreshFix : PolygonFreshFixSource {
        val requested = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Location?>()
        override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
            requested.complete(Unit)
            return answer.await()
        }
    }

    /** Records each request's priority and answers with a copy of [answer]. */
    private class RecordingFreshFix : PolygonFreshFixSource {
        val priorities = mutableListOf<PolygonFixPriority>()
        var answer: Location? = null

        override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
            priorities += priority
            return answer?.let { Location(it) }
        }
    }

    /** At the venue centre, ~52 m from the nearest edge, so at 8 m of error the whole fix is inside. */
    private fun fixAtTheVenueCentre(elapsedRealtimeNanos: Long) =
        fix(37.7750, -122.4194, accuracyMeters = 8f, elapsedRealtimeNanos = elapsedRealtimeNanos)

    /**
     * 5.6 m inside the southern edge at 40 m of error: under the ~54 m arrival ceiling, so judged and
     * agreeing with INSIDE, while its uncertainty reaches well past the ring.
     */
    private fun marginalFixInsideTheVenue(elapsedRealtimeNanos: Long) =
        fix(37.77455, -122.4194, accuracyMeters = 40f, elapsedRealtimeNanos = elapsedRealtimeNanos)

    /** Wall time matches the boot stamp, as a provider with a correct clock reports it. */
    private fun fix(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        elapsedRealtimeNanos: Long
    ) = Location("test").apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = accuracyMeters
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = WALL_CLOCK_AT_BOOT_MS + elapsedRealtimeNanos / NANOS_PER_MILLI
    }

    // ~111 m x 106 m, the same ring as PolygonFreshFixTest's venue, with a 60 s dwell threshold.
    private fun venueRegion() = GeofenceRegion(
        id = VENUE_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 101f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        ),
        dwellThresholdSeconds = 60
    )

    /**
     * A ~300 m square body round the venue centre with a ~1 km x 10 m spur off its east side. The
     * spur adds perimeter faster than area: scale ~63 m, body centre ~150 m deep.
     */
    private fun spurredVenueRegion() = GeofenceRegion(
        id = VENUE_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        // Covers the spur's far end, ~1.15 km out.
        radius = 1_200f,
        polygonVertices = listOf(
            PolygonCoordinate(37.77365, -122.42111),
            PolygonCoordinate(37.77365, -122.41769),
            PolygonCoordinate(37.774955, -122.41769),
            PolygonCoordinate(37.774955, -122.40631),
            PolygonCoordinate(37.775045, -122.40631),
            PolygonCoordinate(37.775045, -122.41769),
            PolygonCoordinate(37.77635, -122.41769),
            PolygonCoordinate(37.77635, -122.42111)
        ),
        dwellThresholdSeconds = 60
    )

    /** ~110 m inside the body's southern edge, ~150 m from the others, at 100 m of error. */
    private fun coarseFixInsideTheSpurredBody(elapsedRealtimeNanos: Long) =
        fix(37.77464, -122.4194, accuracyMeters = 100f, elapsedRealtimeNanos = elapsedRealtimeNanos)

    private companion object {
        const val USER_ID = "user-1"
        const val VENUE_ID = "venue"
        const val BOOT_SESSION_ID = "boot"
        const val MILLIS_PER_SECOND = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L

        /** Far from zero, so a wall stamp can't pass for an unset one. */
        const val WALL_CLOCK_AT_BOOT_MS = 1_760_000_000_000L
    }
}
