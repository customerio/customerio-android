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
 * One fix delivered through several callbacks, and a marginal arrival through the controller.
 *
 * A marginal ENTER is held for corroboration and must log `arrival.pending`; otherwise a capture
 * cannot tell it from a callback that never arrived. A fence may also stay silent because the
 * per-fence replay guard in [PolygonRouteProcessor] already saw the fix's timestamp, which is
 * benign.
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

        // The neighbour's callback arrives first: already inside its wake circle and active.
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
            passiveMonitor = NoopPassiveMonitor,
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

        // Second delivery, same fix and elapsedRealtimeNanos, naming the venue for the first time.
        // 52 m inside at 5 m accuracy, so decisive on its own.
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fix,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getEnteredIds() shouldContain VENUE_ID
    }

    /**
     * Control: the test above without the first delivery. If this fails too, the fixture cannot
     * produce the arrival at all.
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
     * An EXIT batch naming a polygon and the movement trigger. The trigger is handed off as a Job,
     * so the neighbour's coarse EXIT consumes the fix first; the trigger then activates every
     * routable polygon whose wake circle the fix reaches and evaluates the same fix again.
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
     * 10 m inside the ring at 18 m accuracy, so [PolygonAccuracyEvaluator] sets
     * `requiresCorroboration` and the arrival is held. The tests above use a 52 m-inside fix,
     * which commits on its own.
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
     * Control for the test above: a second agreeing fix inside the window completes the arrival,
     * which is only possible if the first fix was held rather than discarded.
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
            triggeringLocation = fieldFix(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                latitudeDelta = 0.000018
            ),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getEnteredIds() shouldContain VENUE_ID
    }

    /**
     * A stationary device indoors cannot produce a second measurement: the fused provider re-emits
     * the held coordinate under a fresh stamp. A repeat is not evidence the device is elsewhere, so
     * the arrival is reported, marked not independent.
     */
    @Test
    fun activate_givenTheHeldPositionDeliveredAgain_expectTheArrivalIsReportedUnconfirmed() = runTest {
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fieldFix(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        store.getEnteredIds() shouldNotContain VENUE_ID

        // Same coordinate, newer stamp: the re-emission, not a second measurement.
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = fieldFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getEnteredIds() shouldContain VENUE_ID
        verify(exactly = 1) {
            mockLogger.logPolygonDecided(
                VENUE_ID,
                "ENTER",
                any(),
                any(),
                any(),
                corroborated = false,
                uncorroboratedReason = PolygonArrivalCommit.NOT_INDEPENDENT
            )
        }
    }

    // ~10 m inside the ring's southern edge at 18 m accuracy, so the arrival needs corroboration.
    private fun fieldFix(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L,
        /** About 2 m north when set, so the fix is independent rather than a re-delivery. */
        latitudeDelta: Double = 0.0
    ) = Location("test").apply {
        latitude = 37.77459 + latitudeDelta
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
