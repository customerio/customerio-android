package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionEmitter
import io.customer.geofence.PolygonArrivalCommit
import io.customer.geofence.PolygonArrivalExpiry
import io.customer.geofence.PolygonCallbackDrop
import io.customer.geofence.PolygonEvaluationSkip
import io.customer.geofence.PolygonSamplingSkip
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * One test per instrumented drop/skip path, driven down its own branch. Not covered: mid-pass
 * re-checks of a condition an outer gate already checked, which need a race to reach.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonDropVisibilityTest : RobolectricTest() {

    private val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var engine: PolygonLocationEngine
    private lateinit var controller: PolygonGeofenceServiceController

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))

        every { secureUserStore.getUserId() } returns USER_ID
        every { clock.currentTimeSeconds() } returns 100L
        every { clock.currentTimeMillis() } returns 100_000L
        coEvery {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns GeofenceTransitionEmitter.Result.PERSISTED
        coEvery { emitter.recoverPendingTransitions() } returns true
        coEvery { manager.replaceMovementTrigger(any()) } returns Result.success(Unit)

        store.beginUserSession(USER_ID)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))

        engine = PolygonLocationEngine(
            store = store,
            transitionProcessor = GeofenceBusinessTransitionProcessor(
                store,
                secureUserStore,
                emitter,
                mockLogger
            ),
            clock = clock,
            logger = mockLogger
        )
        controller = PolygonGeofenceServiceController(
            context = applicationMock,
            store = store,
            engine = engine,
            approachMonitor = approachMonitor,
            manager = manager,
            secureUserStore = secureUserStore,
            freshFixSource = NeverAnswersFreshFix,
            recheckScheduler = NoopRecheckScheduler,
            passiveMonitor = NoopPassiveMonitor,
            logger = mockLogger
        )
    }

    // ---------- engine: evaluation skipped ----------

    @Test
    fun engine_givenAStaleUserStateGeneration_expectTheSkipIsLogged() = runTest {
        engine.processResponsiveLocation(
            location = insideFix(),
            expectedUserStateGeneration = store.userStateGeneration() - 1
        )

        verify { mockLogger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED) }
    }

    @Test
    fun engine_givenTheOutboxCannotDrain_expectTheSkipIsLogged() = runTest {
        coEvery { emitter.recoverPendingTransitions() } returns false

        engine.processResponsiveLocation(insideFix())

        verify { mockLogger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.OUTBOX_BLOCKED) }
    }

    @Test
    fun engine_givenNoActivePolygon_expectTheSkipIsLogged() = runTest {
        engine.activate(VENUE_ID)
        store.deactivatePolygon(VENUE_ID)

        engine.processResponsiveLocation(insideFix())

        verify { mockLogger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.NO_EVALUABLE_FENCES) }
    }

    @Test
    fun engine_givenAnActivePolygonWhoseRingCannotBeBuilt_expectTheSkipNamesEvaluability() = runTest {
        store.saveCachedRegions(listOf(venueRegion().copy(polygonVertices = degenerateRing())))
        store.activatePolygon(VENUE_ID)
        store.recordPolygonCoarseInside(VENUE_ID)
        engine.activate(VENUE_ID)

        engine.processResponsiveLocation(insideFix())

        verify { mockLogger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.NO_EVALUABLE_FENCES) }
    }

    @Test
    fun engine_givenTheUserSignsOutWhileTheOutboxDrains_expectTheSkipIsLogged() = runTest {
        // Recovery awaits IO with no lock held, so a sign-out can land inside it. A side effect,
        // not a stubbed call count, so the test does not depend on how often the engine reads it.
        coEvery { emitter.recoverPendingTransitions() } answers {
            store.clearAll()
            true
        }

        engine.processResponsiveLocation(
            location = insideFix(),
            expectedUserStateGeneration = store.userStateGeneration()
        )

        verify { mockLogger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.USER_STATE_CHANGED) }
    }

    // ---------- controller: callback dropped ----------

    @Test
    fun activate_givenADuplicateDeliveryOfTheSameFix_expectTheDropIsLogged() = runTest {
        val fix = insideFix()
        controller.activate(VENUE_ID, fix, store.userStateGeneration(), null)

        controller.activate(VENUE_ID, fix, store.userStateGeneration(), null)

        verify {
            mockLogger.logPolygonCallbackDropped(PolygonCallbackDrop.DUPLICATE_DELIVERY, VENUE_ID)
        }
    }

    @Test
    fun onCoarseExit_givenTheFenceIsNotRoutable_expectTheDropIsLogged() = runTest {
        store.saveRoutableRegisteredIds(emptySet())

        controller.onCoarseExit(VENUE_ID, insideFix(), store.userStateGeneration(), null)

        verify {
            mockLogger.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, VENUE_ID)
        }
    }

    @Test
    fun onMovementTriggerExit_givenNoPolygonWithinReach_expectTheDropIsLogged() = runTest {
        controller.onMovementTriggerExit(
            triggeringLocation = farAwayFix(),
            expectedUserStateGeneration = store.userStateGeneration()
        )

        verify {
            mockLogger.logPolygonCallbackDropped(PolygonCallbackDrop.NO_POLYGON_IN_RANGE)
        }
    }

    // ---------- controller: the approach sampling loop ----------

    @Test
    fun sampling_givenAnEmptyBatch_expectTheSkipIsLogged() = runTest {
        controller.processApproachLocations(emptyList(), store.userStateGeneration(), 0L)

        verify { mockLogger.logPolygonSamplingSkipped(PolygonSamplingSkip.EMPTY_BATCH) }
    }

    @Test
    fun sampling_givenAStaleSession_expectTheSkipNamesTheSessionAndStops() = runTest {
        val decision = controller.processApproachLocations(
            listOf(insideFix()),
            store.userStateGeneration() - 1,
            0L
        )

        decision shouldBeEqualTo PolygonSamplingDecision.STALE
        verify { mockLogger.logPolygonSamplingSkipped(PolygonSamplingSkip.NOT_CURRENT_SESSION) }
    }

    @Test
    fun sampling_givenAFixWithNoUsableAccuracy_expectTheSkipIsLogged() = runTest {
        controller.processApproachLocations(
            listOf(Location("test").apply { latitude = 37.7750; longitude = -122.4194 }),
            store.userStateGeneration(),
            0L
        )

        verify { mockLogger.logPolygonSamplingSkipped(PolygonSamplingSkip.NO_USABLE_FIX) }
    }

    @Test
    fun sampling_givenNoPolygonWithinReachOfTheSample_expectTheSkipIsLogged() = runTest {
        controller.processApproachLocations(
            listOf(farAwayFix()),
            store.userStateGeneration(),
            0L
        )

        verify { mockLogger.logPolygonSamplingSkipped(PolygonSamplingSkip.NO_POLYGON_IN_RANGE) }
    }

    // ---------- route processor: a held arrival dying ----------

    @Test
    fun route_givenAHeldArrivalAndNoCorroborationInTime_expectTheExpiryIsRecorded() {
        val processor = PolygonRouteProcessor()
        val fence = PolygonFence(VENUE_ID, PolygonGeometry.from(venueVertices()))
        val marginal = PolygonLocationSample(PolygonCoordinate(37.77455, -122.4194), 18.0)

        processor.process(listOf(fence), marginal, 1_000_000_000L, 0.0, emptyMap())
        val afterTheGap = processor.process(listOf(fence), marginal, 301_000_000_000L, 0.0, emptyMap())

        afterTheGap.records
            .filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .map { it.geofenceId to it.reason } shouldBeEqualTo
            listOf(VENUE_ID to PolygonArrivalExpiry.WINDOW_ELAPSED)
    }

    @Test
    fun route_givenAFenceThatNeverHeldAnArrival_expectNoExpiryIsRecorded() {
        val processor = PolygonRouteProcessor()
        val fence = PolygonFence(VENUE_ID, PolygonGeometry.from(venueVertices()))
        // Far outside the ring, so every pass agrees with the committed OUTSIDE state.
        val outside = PolygonLocationSample(PolygonCoordinate(37.9000, -122.4194), 5.0)

        val first = processor.process(listOf(fence), outside, 1_000_000_000L, 0.0, emptyMap())
        val second = processor.process(listOf(fence), outside, 301_000_000_000L, 0.0, emptyMap())

        (first.records + second.records)
            .filterIsInstance<PolygonRouteRecord.ArrivalExpired>() shouldBeEqualTo emptyList()
    }

    @Test
    fun route_givenAHoldThatWasHonouredByADecisiveFix_expectNoExpiryIsRecorded() {
        val processor = PolygonRouteProcessor()
        val fence = PolygonFence(VENUE_ID, PolygonGeometry.from(venueVertices()))
        val marginal = PolygonLocationSample(PolygonCoordinate(37.77455, -122.4194), 18.0)
        val decisive = PolygonLocationSample(PolygonCoordinate(37.7750, -122.4194), 18.0)

        processor.process(listOf(fence), marginal, 1_000_000_000L, 0.0, emptyMap())
        val committed = processor.process(listOf(fence), decisive, 6_000_000_000L, 0.0, emptyMap())
        val laterPass = processor.process(
            listOf(fence),
            decisive,
            131_000_000_000L,
            0.0,
            mapOf(VENUE_ID to PolygonCommittedState.INSIDE)
        )

        committed.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        (committed.records + laterPass.records)
            .filterIsInstance<PolygonRouteRecord.ArrivalExpired>() shouldBeEqualTo emptyList()
    }

    @Test
    fun engine_givenTheSessionEndsWhileAnArrivalIsHeld_expectTheDiscardIsLogged() = runTest {
        store.activatePolygon(VENUE_ID)
        store.recordPolygonCoarseInside(VENUE_ID)
        engine.activate(VENUE_ID)
        engine.processResponsiveLocation(marginalFix())

        // Through the controller, not engine.stop(): the engine returns the discarded ids and the
        // controller logs them after releasing controllerLock.
        controller.stopAll()

        verify {
            mockLogger.logPolygonArrivalExpired(
                VENUE_ID,
                PolygonArrivalExpiry.SESSION_ENDED,
                any()
            )
        }
    }

    // ~10 m inside the ring at 18 m accuracy, so the arrival is held rather than committed.
    private fun marginalFix() = Location("test").apply {
        latitude = 37.77459
        longitude = -122.4194
        accuracy = 18f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        time = 100_000L
    }

    @Test
    fun route_givenAHoldEndedByACoarseFix_expectTheCommitIsRecordedWithItsReason() {
        // 120 m is too coarse to judge a 54 m venue, so the hold is reported rather than broken.
        val processor = PolygonRouteProcessor()
        val fence = PolygonFence(VENUE_ID, PolygonGeometry.from(venueVertices()))
        val marginal = PolygonLocationSample(PolygonCoordinate(37.77455, -122.4194), 18.0)
        val tooCoarse = PolygonLocationSample(PolygonCoordinate(37.774568, -122.4194), 120.0)

        processor.process(listOf(fence), marginal, 1_000_000_000L, 0.0, emptyMap())
        val ended = processor.process(listOf(fence), tooCoarse, 6_000_000_000L, 0.0, emptyMap())

        ended.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        ended.records
            .filterIsInstance<PolygonRouteRecord.Decided>()
            .map { it.geofenceId to it.uncorroboratedReason } shouldBeEqualTo
            listOf(VENUE_ID to PolygonArrivalCommit.UNJUDGEABLE)
    }

    @Test
    fun route_givenAHoldBrokenByAFixPositivelyOutside_expectTheBreakIsRecorded() {
        // 40 m outside at 5 m accuracy: a judged fix reading outside breaks the hold.
        val processor = PolygonRouteProcessor()
        val fence = PolygonFence(VENUE_ID, PolygonGeometry.from(venueVertices()))
        val marginal = PolygonLocationSample(PolygonCoordinate(37.77455, -122.4194), 18.0)
        val clearlyOutside = PolygonLocationSample(PolygonCoordinate(37.77414, -122.4194), 5.0)

        processor.process(listOf(fence), marginal, 1_000_000_000L, 0.0, emptyMap())
        val broken = processor.process(listOf(fence), clearlyOutside, 6_000_000_000L, 0.0, emptyMap())

        broken.detections shouldBeEqualTo emptyList()
        broken.records
            .filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .map { it.geofenceId to it.reason } shouldBeEqualTo
            listOf(VENUE_ID to PolygonArrivalExpiry.EVIDENCE_BROKEN)
    }

    private fun insideFix() = fix(37.7750, -122.4194)

    private fun farAwayFix() = fix(37.9000, -122.4194)

    private fun fix(latitude: Double, longitude: Double) = Location("test").apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = 5f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        time = 100_000L
    }

    private fun venueVertices() = listOf(
        PolygonCoordinate(37.7745, -122.4200),
        PolygonCoordinate(37.7745, -122.4188),
        PolygonCoordinate(37.7755, -122.4188),
        PolygonCoordinate(37.7755, -122.4200)
    )

    // Collinear, so PolygonGeometry.fromOrNull rejects it and the fence has no usable ring.
    private fun degenerateRing() = listOf(
        PolygonCoordinate(37.7745, -122.4200),
        PolygonCoordinate(37.7750, -122.4200),
        PolygonCoordinate(37.7755, -122.4200)
    )

    private fun venueRegion() = GeofenceRegion(
        id = VENUE_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 400f,
        polygonVertices = venueVertices()
    )

    private companion object {
        const val USER_ID = "user-1"
        const val VENUE_ID = "venue"
    }
}
