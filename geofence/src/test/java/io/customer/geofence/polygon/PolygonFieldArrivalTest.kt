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
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * The 2026-09-17 lost arrival, and what actually caused it.
 *
 * The fence was **not** dropped. It was evaluated, decided ENTER, and then withheld pending a
 * second agreeing fix, and that branch emitted nothing at all — the only path in the pipeline that
 * consumes a fix, decides something, and stays silent. In a capture it is indistinguishable from a
 * callback that never arrived, which is why three reproduction attempts went looking in the
 * delivery path.
 *
 * ```
 * 18:09:37.364  ids=16,cio_movement_trigger  exit   acc=18.0 age=0.2  -> evaluated 16, 27, 17
 * 18:09:37.549  ids=27,17,25                 enter  acc=18.0 age=0.4  -> silent; fence 25 held here
 * ```
 *
 * The fix was ~10 m inside the ring at 18 m accuracy, so its own uncertainty reached the boundary
 * and [PolygonAccuracyEvaluator] set `requiresCorroboration`. An iOS device logged the same venue
 * at `edge=+10 acc=5.1`, which is what pins this as a real arrival rather than an inferred one.
 *
 * Fences 27 and 17 were silent in that same callback for an unrelated and benign reason: the
 * per-fence replay guard in [PolygonRouteProcessor] had already seen this fix's nanos. Three silent
 * ids looked like one blackout and were two different things.
 *
 * The first three tests are the delivery-path orderings that were suspected and cleared. They are
 * kept as regression cover, not as evidence for this change: each one passes with or without it.
 * Only `activate_givenAMarginalFieldFix...` fails if the instrumentation is reverted.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonFieldArrivalTest : RobolectricTest() {

    private val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)

    private lateinit var store: GeofenceRegionStoreImpl
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
        store.saveCachedRegions(listOf(neighbourRegion(), venueRegion()))
        store.saveRegisteredIds(setOf(NEIGHBOUR_ID, VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(NEIGHBOUR_ID, VENUE_ID))

        // The neighbour is the fence whose callback arrives first: already inside its wake circle
        // and already being evaluated, exactly as fences 16, 27 and 17 were on the drive.
        store.activatePolygon(NEIGHBOUR_ID)
        store.recordPolygonCoarseInside(NEIGHBOUR_ID)

        controller = PolygonGeofenceServiceController(
            context = applicationMock,
            store = store,
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
            ),
            approachMonitor = approachMonitor,
            manager = manager,
            secureUserStore = secureUserStore,
            freshFixSource = NeverAnswersFreshFix,
            recheckScheduler = NoopRecheckScheduler,
            logger = mockLogger
        )
    }

    @Test
    fun activate_givenTheSameFixAlreadyDeliveredForAnotherFence_expectTheNewFenceStillEnters() = runTest {
        val fix = fixInsideTheVenue()

        // First delivery: the neighbour's coarse EXIT. The venue is not named here and is not yet
        // active, so this callback cannot decide anything about it.
        controller.onCoarseExit(
            polygonId = NEIGHBOUR_ID,
            triggeringLocation = fix,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        // Second delivery, same fix object and the same elapsedRealtimeNanos, naming the venue for
        // the first time. The device is 52 m inside a 5 m fix, so this is decisive on its own.
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fix,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getEnteredIds() shouldContain VENUE_ID
    }

    /**
     * The control. Identical to the test above with the first delivery removed, which is the only
     * difference between them. If this fails too, the fixture never could have produced the arrival
     * and the test above proves nothing about duplicate delivery.
     */
    @Test
    fun activate_givenNoEarlierDeliveryOfTheFix_expectTheFenceEnters() = runTest {
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getEnteredIds() shouldContain VENUE_ID
    }

    /**
     * The field sequence, faithfully: the EXIT broadcast carried `ids=16,cio_movement_trigger`.
     *
     * The receiver sorts the movement trigger ahead of polygons and hands it off as a Job, so the
     * neighbour's coarse EXIT runs first and consumes the fix. The movement trigger's own body then
     * activates every routable polygon whose wake circle the fix reaches, which on the drive
     * included the venue at edge distance 0, and evaluates the same fix again.
     *
     * The venue is activated by a path that runs after the fix was already consumed for the fences
     * active at the time. This is the ordering the two tests above do not have.
     */
    @Test
    fun movementTriggerExit_givenTheFixAlreadyConsumedByACoarseExit_expectTheVenueStillEnters() = runTest {
        val fix = fixInsideTheVenue()

        controller.onCoarseExit(
            polygonId = NEIGHBOUR_ID,
            triggeringLocation = fix,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        controller.onMovementTriggerExit(
            triggeringLocation = fix,
            expectedUserStateGeneration = store.userStateGeneration()
        )

        store.getEnteredIds() shouldContain VENUE_ID
    }

    /**
     * The real cause of the 2026-09-17 lost arrival, and it is not a dropped callback.
     *
     * At the field's own geometry the fix is 10 m inside the ring with 18 m accuracy, so its own
     * uncertainty reaches the boundary and [PolygonAccuracyEvaluator] sets `requiresCorroboration`.
     * The arrival is decided and then held for a second agreeing fix. Correct by design; the defect
     * was that the branch emitted nothing, so a held arrival and a callback that never arrived
     * looked identical in a capture.
     *
     * The three tests above pass because their fix sits 52 m inside a 5 m circle, which corroborates
     * on its own. That comfortable fixture is why three reproduction attempts came back green.
     */
    @Test
    fun activate_givenAMarginalFieldFix_expectTheArrivalIsHeldAndTheHoldIsLogged() = runTest {
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fieldFix(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getEnteredIds() shouldNotContain VENUE_ID
        verify(exactly = 1) {
            mockLogger.logPolygonArrivalPending(VENUE_ID, any(), any(), any())
        }
    }

    /**
     * The control for the test above: the hold is real evidence, not a discard. A second agreeing
     * fix inside the corroboration window completes the arrival, which is only possible if the
     * first fix reached the evaluator.
     */
    @Test
    fun activate_givenASecondAgreeingMarginalFix_expectTheArrivalCompletes() = runTest {
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fieldFix(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fieldFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getEnteredIds() shouldContain VENUE_ID
    }

    // ~10 m north of the ring's southern edge, at the accuracy GMS actually reported on the drive.
    private fun fieldFix(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
    ) = Location("test").apply {
        latitude = 37.77459
        longitude = -122.4194
        accuracy = 18f
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = 100_000L
    }

    private fun fixInsideTheVenue() = Location("test").apply {
        latitude = 37.7750
        longitude = -122.4194
        accuracy = 5f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        time = 100_000L
    }

    // Roughly 110 m x 105 m, so the fix at its centre sits ~52 m from the nearest edge.
    private fun venueRegion() = GeofenceRegion(
        id = VENUE_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 400f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    )

    // About 1.1 km north, so the fix above reads decisively outside it.
    private fun neighbourRegion() = GeofenceRegion(
        id = NEIGHBOUR_ID,
        latitude = 37.7850,
        longitude = -122.4194,
        radius = 400f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7845, -122.4200),
            PolygonCoordinate(37.7845, -122.4188),
            PolygonCoordinate(37.7855, -122.4188),
            PolygonCoordinate(37.7855, -122.4200)
        )
    )

    private companion object {
        const val USER_ID = "user-1"
        const val VENUE_ID = "venue"
        const val NEIGHBOUR_ID = "neighbour"
    }
}
