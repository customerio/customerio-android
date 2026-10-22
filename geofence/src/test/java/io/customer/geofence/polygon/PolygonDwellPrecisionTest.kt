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
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceCooldownFilter
import io.customer.geofence.GeofenceDwellCoordinator
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofenceRegion
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
    private val dwellChecks = RecordingDwellCheckScheduler()

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
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))

        every { secureUserStore.getUserId() } returns USER_ID
        // Both clocks advance with the shadow clock, so wall stamps keep agreeing with the boot clock.
        every { clock.elapsedRealtime() } answers { SystemClock.elapsedRealtime() }
        every { clock.currentTimeMillis() } answers { wallNowMs() }
        every { clock.currentTimeSeconds() } answers { wallNowMs() / MILLIS_PER_SECOND }
        every { cooldownFilter.suppressedForSeconds(any(), any(), any()) } returns null
        coEvery { manager.replaceMovementTrigger(any()) } returns Result.success(Unit)
        coEvery { manager.removeGeofencesByIds(any()) } returns Result.success(Unit)

        store.beginUserSession(USER_ID)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))
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
    fun activate_givenEveryPreciseFixStaysMarginal_expectTheVisitStopsAskingAfterItsBudget() = runTest {
        // A venue the device's fixes can never fit inside: each retry window would otherwise buy one more
        // high-accuracy request for as long as the device stays parked.
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        repeat(GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT + 2) {
            freshFix.answer = marginalFixInsideTheVenue(SystemClock.elapsedRealtimeNanos())
            wakeWithMarginalFix(controller)
            ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        }

        freshFix.priorities.size shouldBeEqualTo GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        visit.dwellProofRequests shouldBeEqualTo GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT
        verify(exactly = 1) {
            logger.logDwellProofBudgetSpent(VENUE_ID, GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT)
        }
        dwells().shouldBeEmpty()

        // Spent, the visit can still be proven by a fix that arrives for any other reason.
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        freshFix.priorities.size shouldBeEqualTo GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT
        dwells().map { it.visitId } shouldBeEqualTo listOf(visit.visitId)
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
    fun onDwellCheck_givenADueVisit_expectOnlyAPreciseFixQualifiesAndEachCheckIsCharged() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        // The visit's start re-plans the background checks.
        val afterArrival = dwellChecks.reconciles
        (afterArrival > 0) shouldBeEqualTo true

        // Due, but no precise fix comes. A check is not proof: no DWELL, and the request is spent.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        dwellCheck(controller, visit.visitId) shouldBeEqualTo PolygonDwellCheckResult.Charged
        freshFix.priorities shouldBeEqualTo listOf(PolygonFixPriority.HIGH_ACCURACY)
        dwells().shouldBeEmpty()
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().dwellProofRequests shouldBeEqualTo 1
        // The charge moved the next check past the spacing, so it re-planned.
        dwellChecks.reconciles shouldBeEqualTo afterArrival + 1

        // The next check, past the spacing: its precise fix shows the stay.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        val precise = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        freshFix.answer = precise
        dwellCheck(controller, visit.visitId)

        freshFix.priorities.size shouldBeEqualTo 2
        val dwell = dwells().single()
        dwell.visitId shouldBeEqualTo visit.visitId
        dwell.timestamp shouldBeEqualTo precise.time / MILLIS_PER_SECOND
        dwell.detectionSource shouldBeEqualTo "location_evidence"
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().dwellProofRequests shouldBeEqualTo 2

        // A late duplicate of the same check finds the visit settled: no request, no second DWELL.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        dwellCheck(controller, visit.visitId)
        freshFix.priorities.size shouldBeEqualTo 2
        dwells().size shouldBeEqualTo 1
    }

    @Test
    fun onDwellCheck_givenAnotherRequestsCooldown_expectAnUncountedRetryAndNoCharge() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        // A callback with no fix asks for the edge, uncharged, and starts the shared cooldown, as a
        // neighbouring fence's request would.
        controller.activate(VENUE_ID, triggeringLocation = null, expectedUserStateGeneration = store.userStateGeneration())
        advanceUntilIdle()
        val requestsBefore = freshFix.priorities.size
        requestsBefore shouldBeEqualTo 1
        val spentBefore = store.getDwellVisit(VENUE_ID).shouldNotBeNull().dwellProofRequests

        val result = dwellCheck(controller, visit.visitId)

        result shouldBeEqualTo PolygonDwellCheckResult.RetryAfter(30_000L)
        freshFix.priorities.size shouldBeEqualTo requestsBefore
        store.getDwellVisit(VENUE_ID).shouldNotBeNull().dwellProofRequests shouldBeEqualTo spentBefore
    }

    @Test
    fun onDwellCheck_givenPolygonMonitoringStopped_expectNothingRequestedAndItStaysStopped() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        freshFix.answer = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        controller.stopAll()
        io.mockk.clearMocks(approachMonitor, answers = false)

        // A check planned before the stop finds the polygon inactive: it must not undo the stop.
        dwellCheck(controller, visit.visitId) shouldBeEqualTo PolygonDwellCheckResult.Uncharged
        freshFix.priorities.shouldBeEmpty()
        store.getActivePolygonIds().contains(VENUE_ID) shouldBeEqualTo false
        verify(exactly = 0) { approachMonitor.start(any()) }

        // Monitoring resumes with the next coarse ENTER, which re-plans the visit's checks.
        val reconcilesBefore = dwellChecks.reconciles
        controller.activate(VENUE_ID, expectedUserStateGeneration = store.userStateGeneration()) shouldBeEqualTo true
        dwellChecks.reconciles shouldBeEqualTo reconcilesBefore + 1
    }

    @Test
    fun onMovementTriggerExit_givenAPolygonStoppedWithItsVisitKept_expectTheReactivationReplansTheChecks() = runTest {
        val controller = controller(RecordingFreshFix())
        arriveClearly(controller)
        controller.stopAll()
        store.getActivePolygonIds().contains(VENUE_ID) shouldBeEqualTo false
        val reconcilesBefore = dwellChecks.reconciles

        controller.onMovementTriggerExit(fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()))
        advanceUntilIdle()

        store.getActivePolygonIds() shouldContain VENUE_ID
        (dwellChecks.reconciles > reconcilesBefore) shouldBeEqualTo true
    }

    @Test
    fun insideEvidence_givenAnUndatedVisit_expectTheRepairReplansTheChecks() = runTest {
        val controller = controller(RecordingFreshFix())
        arriveClearly(controller)
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        // A visit an older SDK stored without boot-clock timing: no check could measure it.
        store.saveDwellVisit(visit.copy(bootSessionId = null, enteredAtElapsedMs = null)) shouldBeEqualTo true
        val reconcilesBefore = dwellChecks.reconciles

        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        controller.activate(VENUE_ID, fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()), store.userStateGeneration())
        advanceUntilIdle()

        store.getDwellVisit(VENUE_ID).shouldNotBeNull().enteredAtElapsedMs.shouldNotBeNull()
        (dwellChecks.reconciles > reconcilesBefore) shouldBeEqualTo true
    }

    @Test
    fun onDwellCheck_givenAFixFromMomentsBeforeTheThreshold_expectARetryAfterTheCooldown() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        // Just before the threshold another request's precise fix arrives, then the check runs just
        // after it, inside the batch reuse window, and is answered by that pre-threshold fix.
        val enteredAt = visit.enteredAtElapsedMs.shouldNotBeNull()
        ShadowSystemClock.advanceBy(Duration.ofMillis(enteredAt + 59_500L - SystemClock.elapsedRealtime()))
        freshFix.answer = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        controller.activate(VENUE_ID, triggeringLocation = null, expectedUserStateGeneration = store.userStateGeneration())
        advanceUntilIdle()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000L))

        val result = dwellCheck(controller, visit.visitId)

        // Not an ordinary uncharged check pushed back the full spacing: it looks again after the cooldown.
        result shouldBeEqualTo PolygonDwellCheckResult.RetryAfter(30_000L)
        freshFix.priorities.size shouldBeEqualTo 1
        dwells().shouldBeEmpty()
    }

    @Test
    fun onDwellCheck_givenAnotherVisitOrASpentBudget_expectNothingRequested() = runTest {
        val freshFix = RecordingFreshFix()
        val controller = controller(freshFix)
        arriveClearly(controller)
        val visit = store.getDwellVisit(VENUE_ID).shouldNotBeNull()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        freshFix.answer = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())

        // Planned for a visit that has since been replaced.
        dwellCheck(controller, "an-earlier-visit") shouldBeEqualTo PolygonDwellCheckResult.Uncharged
        freshFix.priorities.shouldBeEmpty()

        // The visit already spent every request.
        store.saveDwellVisit(visit.copy(dwellProofRequests = GeofenceConstants.MAX_DWELL_PROOF_REQUESTS_PER_VISIT)) shouldBeEqualTo true
        dwellCheck(controller, visit.visitId)
        freshFix.priorities.shouldBeEmpty()
        dwells().shouldBeEmpty()
    }

    @Test
    fun processFix_givenDwellReservedButNotStaged_expectEngineReleasesAcquisitionBeforeDelivery() = runTest {
        val fresh = RecordingFreshFix()
        arriveClearly(controller(fresh))
        advanceUntilIdle()
        val visitId = store.getDwellVisit(VENUE_ID).shouldNotBeNull().visitId
        val blocked = mockk<GeofenceBusinessTransitionProcessor>(relaxed = true)
        coEvery { blocked.recoverPendingTransitions() } returns true
        coEvery { blocked.process(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSIST_FAILED
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))

        // An ordinary precise callback re-plans the checks at reservation, before delivery lands.
        val reconcilesBefore = dwellChecks.reconciles
        controller(fresh, processorOverride = blocked).activate(
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
        dwellChecks.reconciles shouldBeEqualTo reconcilesBefore + 1
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
    fun onDwellCheck_givenTeardownAndReactivationWhileEngineRecoversOutbox_expectRetiredPassDropped() = runTest {
        arriveClearly(controller(RecordingFreshFix()))
        advanceUntilIdle()
        val visitId = store.getDwellVisit(VENUE_ID).shouldNotBeNull().visitId
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
        val precise = fixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos())
        val controller = controller(RecordingFreshFix().apply { answer = precise }, processorOverride = blocked)
        val wake = launch { dwellCheck(controller, visitId) }
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
    fun onDwellCheck_givenTeardownAndReactivationWhileAwaitingFix_expectOldAnswerDropped() = runTest {
        val fresh = HoldingFreshFix()
        val controller = controller(fresh)
        arriveClearly(controller)
        advanceUntilIdle()
        val visitId = store.getDwellVisit(VENUE_ID).shouldNotBeNull().visitId
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70))
        val oldWake = launch { dwellCheck(controller, visitId) }
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
        // The current session can use the same fix through its own callback; only the retired check is refused.
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

    private fun TestScope.diagnosticHistory(): Pair<DwellDiagnosticJournal, DwellDiagnosticRecorder> {
        val prefs = applicationMock.getSharedPreferences("cio_test_dwell_controller_history", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val journal = DwellDiagnosticJournal(lazyOf(prefs), { true }, StandardTestDispatcher(testScheduler))
        return journal to DwellDiagnosticRecorder(journal, { true }, this)
    }

    /** The worker's call for a check planned for [visitId], with the tokens it snapshots first. */
    private suspend fun TestScope.dwellCheck(
        controller: PolygonGeofenceServiceController,
        visitId: String
    ): PolygonDwellCheckResult =
        controller.onDwellCheck(
            polygonId = VENUE_ID,
            visitId = visitId,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedTeardownGeneration = controller.teardownGeneration()
        ).also { advanceUntilIdle() }

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
                dwellCheckScheduler = dwellChecks
            ),
            approachMonitor = approachMonitor,
            manager = manager,
            secureUserStore = secureUserStore,
            freshFixSource = freshFixSource,
            recheckScheduler = NoopRecheckScheduler,
            passiveMonitor = NoopPassiveMonitor,
            bootSessionProvider = { BOOT_SESSION_ID },
            logger = logger,
            dwellCheckScheduler = dwellChecks,
            diagnosticRecorder = diagnosticRecorder
        )
    }

    /** Counts re-plans; the checks themselves are driven through [dwellCheck]. */
    private class RecordingDwellCheckScheduler : PolygonDwellCheckScheduler {
        var reconciles = 0
        override fun reconcile() {
            reconciles += 1
        }
        override fun takeDueChecks(): List<PolygonDwellCheck> = emptyList()
        override fun recordResult(check: PolygonDwellCheck, result: PolygonDwellCheckResult) = Unit
        override fun beginRun() = Unit
        override fun endRun() = Unit
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
