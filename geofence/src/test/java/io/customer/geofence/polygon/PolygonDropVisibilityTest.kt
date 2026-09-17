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
 * One test per instrumented path, because instrumentation whose whole value is being present on a
 * specific branch is not covered by a test that only proves the log method works.
 *
 * A peer review found 8 of 11 call sites surviving individual deletion with the suite still green;
 * re-measuring after more sites were added put it at 14 of 17. Instrumentation whose whole value is
 * being on one branch is not covered by a test that only proves the log method works, so there is a
 * test per site here.
 *
 * Of 23 sites, 16 are killed by these tests and by [PolygonFieldArrivalTest]. The other 7 are
 * **expected to be unreachable**, and that is the point of listing them rather than an apology for
 * not testing them. Each is a mid-pass re-check of a condition an outer gate already held: the
 * user-state re-checks inside [PolygonLocationEngine.processLocations]' per-location loop, the two
 * inside [PolygonGeofenceServiceController.processApproachLocations]' loop, and the inner
 * registration re-checks in `activate` and `onCoarseExit`. Reaching one in a test means building a
 * concurrency harness per site to prove a log line, and a stubbed version would mostly prove the
 * stub can be made to lie.
 *
 * So the field is the instrument for these: a capture containing `why=user_state_changed` from
 * inside the per-location loop means an identify landed mid-pass, which is a real finding rather
 * than noise. Treat each of the 7 as an assertion the field can falsify.
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
        // An older transition still cannot reach the durable queue, so a newer edge is not judged.
        coEvery { emitter.recoverPendingTransitions() } returns false

        engine.processResponsiveLocation(insideFix())

        verify { mockLogger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.OUTBOX_BLOCKED) }
    }

    @Test
    fun engine_givenNoActivePolygon_expectTheSkipIsLogged() = runTest {
        // Nothing was ever activated, so the fix is accepted and judged against nothing.
        engine.activate(VENUE_ID)
        store.deactivatePolygon(VENUE_ID)

        engine.processResponsiveLocation(insideFix())

        verify { mockLogger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.NO_EVALUABLE_FENCES) }
    }

    @Test
    fun engine_givenAnActivePolygonWhoseRingCannotBeBuilt_expectTheSkipNamesEvaluability() = runTest {
        // The reason this is NO_EVALUABLE_FENCES and not "no active polygon": the fence is active,
        // its ring just does not validate, and claiming nothing was active would be false.
        store.saveCachedRegions(listOf(venueRegion().copy(polygonVertices = degenerateRing())))
        store.activatePolygon(VENUE_ID)
        store.recordPolygonCoarseInside(VENUE_ID)
        engine.activate(VENUE_ID)

        engine.processResponsiveLocation(insideFix())

        verify { mockLogger.logPolygonEvaluationSkipped(PolygonEvaluationSkip.NO_EVALUABLE_FENCES) }
    }

    @Test
    fun engine_givenTheUserSignsOutWhileTheOutboxDrains_expectTheSkipIsLogged() = runTest {
        // The re-check after outbox recovery exists because recovery awaits IO with no lock held,
        // so a sign-out can complete inside that gap. Driven by the side effect rather than by
        // stubbing a call count, so the test does not depend on how many times the engine reads it.
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
        // The movement-trigger path that was live during the field event. It reported nothing when
        // it declined, so a capture could not tell a declined refresh from one that never ran.
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
        controller.processApproachLocations(emptyList(), store.userStateGeneration())

        verify { mockLogger.logPolygonSamplingSkipped(PolygonSamplingSkip.EMPTY_BATCH) }
    }

    @Test
    fun sampling_givenAStaleSession_expectTheSkipNamesTheSessionAndStops() = runTest {
        // The only sampling reason that ends the session rather than dropping one sample. If
        // sampling stops for this reason and says nothing, the silence looks like the session
        // never ran at all, which is what the 2026-09-17 capture could not distinguish.
        val decision = controller.processApproachLocations(
            listOf(insideFix()),
            store.userStateGeneration() - 1
        )

        decision shouldBeEqualTo PolygonSamplingDecision.STALE
        verify { mockLogger.logPolygonSamplingSkipped(PolygonSamplingSkip.NOT_CURRENT_SESSION) }
    }

    @Test
    fun sampling_givenAFixWithNoUsableAccuracy_expectTheSkipIsLogged() = runTest {
        controller.processApproachLocations(
            listOf(Location("test").apply { latitude = 37.7750; longitude = -122.4194 }),
            store.userStateGeneration()
        )

        verify { mockLogger.logPolygonSamplingSkipped(PolygonSamplingSkip.NO_USABLE_FIX) }
    }

    @Test
    fun sampling_givenNoPolygonWithinReachOfTheSample_expectTheSkipIsLogged() = runTest {
        controller.processApproachLocations(
            listOf(farAwayFix()),
            store.userStateGeneration()
        )

        verify { mockLogger.logPolygonSamplingSkipped(PolygonSamplingSkip.NO_POLYGON_IN_RANGE) }
    }

    // ---------- route processor: a held arrival dying ----------

    @Test
    fun route_givenAHeldArrivalAndNoCorroborationInTime_expectTheExpiryIsLogged() {
        // The counterpart to the pending record. Without this a capture shows holds and decisions
        // and cannot say which holds died, which is the question the field has to answer.
        val processor = PolygonRouteProcessor(logger = mockLogger)
        val fence = PolygonFence(VENUE_ID, PolygonGeometry.from(venueVertices()))
        val marginal = PolygonLocationSample(PolygonCoordinate(37.77455, -122.4194), 18.0)

        processor.process(listOf(fence), marginal, 1_000_000_000L, 0.0, emptyMap())
        processor.process(listOf(fence), marginal, 301_000_000_000L, 0.0, emptyMap())

        verify { mockLogger.logPolygonArrivalExpired(VENUE_ID, PolygonArrivalExpiry.WINDOW_ELAPSED, any()) }
    }

    @Test
    fun route_givenAFenceThatNeverHeldAnArrival_expectNoExpiryIsLogged() {
        // Found on the 2026-09-17 evening drive: 11 expiry records fired and zero holds preceded
        // them. Every quiet fence was being stamped as a pending arrival, because the same helper
        // is reached to break a run of agreeing fixes, so a minute later each one reported an
        // arrival that had never existed. A record that fires when nothing happened is worse than
        // no record.
        val processor = PolygonRouteProcessor(logger = mockLogger)
        val fence = PolygonFence(VENUE_ID, PolygonGeometry.from(venueVertices()))
        // Far outside the ring, so every pass agrees with the committed OUTSIDE state.
        val outside = PolygonLocationSample(PolygonCoordinate(37.9000, -122.4194), 5.0)

        processor.process(listOf(fence), outside, 1_000_000_000L, 0.0, emptyMap())
        processor.process(listOf(fence), outside, 301_000_000_000L, 0.0, emptyMap())

        verify(exactly = 0) { mockLogger.logPolygonArrivalExpired(any(), any(), any()) }
    }

    @Test
    fun route_givenAHoldThatWasHonouredByADecisiveFix_expectNoExpiryIsLogged() {
        // The stamp is written by confirmArrival but cleared by two other call sites that do not
        // know about it, so a hold that COMPLETED left its timestamp behind and reported an expiry
        // for an arrival that succeeded.
        val processor = PolygonRouteProcessor(logger = mockLogger)
        val fence = PolygonFence(VENUE_ID, PolygonGeometry.from(venueVertices()))
        val marginal = PolygonLocationSample(PolygonCoordinate(37.77455, -122.4194), 18.0)
        val decisive = PolygonLocationSample(PolygonCoordinate(37.7750, -122.4194), 18.0)

        processor.process(listOf(fence), marginal, 1_000_000_000L, 0.0, emptyMap())
        val committed = processor.process(listOf(fence), decisive, 6_000_000_000L, 0.0, emptyMap())
        processor.process(
            listOf(fence),
            decisive,
            131_000_000_000L,
            0.0,
            mapOf(VENUE_ID to PolygonCommittedState.INSIDE)
        )

        committed.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        verify(exactly = 0) { mockLogger.logPolygonArrivalExpired(any(), any(), any()) }
    }

    @Test
    fun engine_givenTheSessionEndsWhileAnArrivalIsHeld_expectTheDiscardIsLogged() = runTest {
        // The field case the pending record exists to explain: sampling goes quiet, the session is
        // torn down, and the hold disappears. The window-elapsed record cannot cover it because
        // that one only fires when another fix arrives, and here no further fix ever does.
        store.activatePolygon(VENUE_ID)
        store.recordPolygonCoarseInside(VENUE_ID)
        engine.activate(VENUE_ID)
        engine.processResponsiveLocation(marginalFix())

        engine.stop()

        verify {
            mockLogger.logPolygonArrivalExpired(
                VENUE_ID,
                PolygonArrivalExpiry.SESSION_ENDED,
                any()
            )
        }
    }

    // ~10 m inside the ring at the accuracy the drive reported, so the arrival is held not committed.
    private fun marginalFix() = Location("test").apply {
        latitude = 37.77459
        longitude = -122.4194
        accuracy = 18f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        time = 100_000L
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
