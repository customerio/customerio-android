package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceBusinessTransitionProcessor
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

        store.beginUserSession(USER_ID)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))
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

    private fun controller(freshFixSource: PolygonFreshFixSource): PolygonGeofenceServiceController {
        val processor = GeofenceBusinessTransitionProcessor(
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
                dwellCoordinator = GeofenceDwellCoordinator(store, processor, clock) { BOOT_SESSION_ID }
            ),
            approachMonitor = approachMonitor,
            manager = manager,
            secureUserStore = secureUserStore,
            freshFixSource = freshFixSource,
            recheckScheduler = NoopRecheckScheduler,
            passiveMonitor = NoopPassiveMonitor,
            bootSessionProvider = { BOOT_SESSION_ID },
            logger = logger
        )
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
