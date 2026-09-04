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
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionEmitter
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * The engine only ever sees fixes some other component already received, and only ever decides from
 * one fix at a time. These tests cover that surface — including what it deliberately refuses to
 * decide, which is as much a part of the contract as what it commits.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonLocationEngineTest : RobolectricTest() {
    private val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var engine: PolygonLocationEngine

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        store.saveCachedRegions(listOf(polygonRegion()))
        store.beginUserSession("user-1")
        store.saveRegisteredIds(setOf(POLYGON_ID))
        store.saveRoutableRegisteredIds(setOf(POLYGON_ID))
        store.activatePolygon(POLYGON_ID)
        store.recordPolygonCoarseInside(POLYGON_ID)
        every { secureUserStore.getUserId() } returns "user-1"
        every { clock.currentTimeSeconds() } returns 100L
        every { clock.currentTimeMillis() } returns 100_000L
        coEvery { emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        coEvery { emitter.recoverPendingTransitions() } returns true
        engine = PolygonLocationEngine(
            store = store,
            transitionProcessor = GeofenceBusinessTransitionProcessor(
                store,
                secureUserStore,
                emitter,
                logger
            ),
            clock = clock,
            logger = logger
        )
    }

    // ---------- the catalog can change under an unchanged active set ----------

    @Test
    fun processResponsiveLocation_givenTheRingMovedUnderTheSameId_expectJudgedAgainstTheNewRing() = runTest {
        // A sync replaces a polygon's geometry without changing which polygons are active. Caching
        // the built fences on the active id set alone would keep evaluating the ring that moved,
        // and report the device inside a fence it is nowhere near.
        engine.processResponsiveLocation(insideFix())
        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)

        // Same id, same active set, ring moved ~1 km north. The old fix is now well outside it.
        store.saveCachedRegions(
            listOf(
                polygonRegion().copy(
                    polygonVertices = listOf(
                        PolygonCoordinate(37.7845, -122.4200),
                        PolygonCoordinate(37.7845, -122.4188),
                        PolygonCoordinate(37.7855, -122.4188),
                        PolygonCoordinate(37.7855, -122.4200)
                    )
                )
            )
        )

        engine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()))

        store.getEnteredIds().shouldBeEmpty()
    }

    // ---------- what a single decisive fix does ----------

    @Test
    fun processResponsiveLocation_givenOneDecisiveInsideFix_expectCommittedEnter() = runTest {
        engine.processResponsiveLocation(insideFix())

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                POLYGON_ID,
                Event.GeofenceTransition.ENTER,
                "user-1",
                100L,
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenSameFixRepeated_expectExactlyOneEnter() = runTest {
        val fix = insideFix()

        repeat(3) { engine.processResponsiveLocation(fix) }

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                any(),
                Event.GeofenceTransition.ENTER,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenConcurrentDeliveriesOfSameFix_expectSerializedSingleEnter() = runTest {
        val fix = insideFix()

        coroutineScope {
            List(8) { async { engine.processResponsiveLocation(fix) } }.awaitAll()
        }

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                any(),
                Event.GeofenceTransition.ENTER,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenDelayedApproachFix_expectObservedFixCanArmSession() = runTest {
        // Play services batches background deliveries. A fix older than the normal 30-second trigger
        // grace is still valid evidence when the approach session was armed from that same fix.
        val observedAt = SystemClock.elapsedRealtimeNanos() - 50_000_000_000L
        engine.activateFromApproach(POLYGON_ID, observedAt)

        engine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = observedAt))

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
    }

    @Test
    fun processResponsiveLocation_givenDelayedFix_expectTransitionUsesOriginalFixTime() = runTest {
        val observedAt = SystemClock.elapsedRealtimeNanos() - 50_000_000_000L
        every { clock.currentTimeMillis() } returns 200_000L
        engine.activateFromApproach(POLYGON_ID, observedAt)

        engine.processResponsiveLocation(
            insideFix(elapsedRealtimeNanos = observedAt, timestampMillis = 150_000L)
        )

        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                POLYGON_ID,
                Event.GeofenceTransition.ENTER,
                "user-1",
                150L,
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    // ---------- best-effort boundary: fixes the engine refuses to decide from ----------

    @Test
    fun processResponsiveLocation_givenAccuracyCircleStraddlingTheRing_expectNoTransition() = runTest {
        // Realistic background accuracy inside a ~110 m polygon: the device is genuinely inside, but
        // its uncertainty circle plus the anti-jitter margin reaches past the ring, so "inside" is not
        // established. A guess here would report ENTER for a device that may be on the pavement.
        engine.processResponsiveLocation(insideFix(accuracyMeters = 45f))

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenFixCoarserThanTheDecisiveCeiling_expectIgnoredAndLogged() = runTest {
        engine.processResponsiveLocation(insideFix(accuracyMeters = 120f))

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenPolygonSmallerThanTypicalAccuracy_expectNoEnterEverCommitted() = runTest {
        // A ~40 m ring has no interior point more than ~20 m from its own boundary, so a fix with
        // ordinary background accuracy can never be decisive anywhere inside it. The device sits dead
        // centre and still produces nothing — and must not fall back to the enclosing trigger circle,
        // which is hundreds of metres wide.
        store.saveCachedRegions(listOf(smallPolygonRegion()))
        store.saveRegisteredIds(setOf(SMALL_POLYGON_ID))
        store.saveRoutableRegisteredIds(setOf(SMALL_POLYGON_ID))
        store.activatePolygon(SMALL_POLYGON_ID)
        store.recordPolygonCoarseInside(SMALL_POLYGON_ID)

        engine.processResponsiveLocation(
            fix(37.7750, -122.4194, accuracyMeters = 20f)
        )

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenVisitEntirelyBetweenTwoFixes_expectMissedRatherThanInvented() = runTest {
        // Approach fixes are displacement-gated and minutes apart. The device drove in and out between
        // them; both fixes it delivered are outside. There is no evidence of a visit, so none is
        // reported — the low-power path reports what it saw, not what it can infer.
        val base = SystemClock.elapsedRealtimeNanos() - 10_000_000_000L

        engine.processResponsiveLocation(fix(37.7750, -122.4175, elapsedRealtimeNanos = base))
        engine.processResponsiveLocation(
            fix(37.7750, -122.4175, elapsedRealtimeNanos = base + 5_000_000_000L)
        )

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenFixWithNoAccuracy_expectIgnoredAndLogged() = runTest {
        val unusable = Location("test").apply {
            latitude = 37.7750
            longitude = -122.4194
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L
            time = 100_000L
        }

        engine.processResponsiveLocation(unusable)

        store.getEnteredIds().shouldBeEmpty()
        verify { logger.logPolygonFixNotUsable(any()) }
    }

    // ---------- identity, generation and catalog fencing ----------

    @Test
    fun processResponsiveLocation_givenFixFromPreviousUserGeneration_expectDoesNotAffectNewSession() = runTest {
        val previousGeneration = store.userStateGeneration()
        store.beginUserSession("user-2")
        store.saveRegisteredIds(setOf(POLYGON_ID))
        store.saveRoutableRegisteredIds(setOf(POLYGON_ID))
        store.activatePolygon(POLYGON_ID)
        store.recordPolygonCoarseInside(POLYGON_ID)
        every { secureUserStore.getUserId() } returns "user-2"

        engine.processResponsiveLocation(
            location = insideFix(),
            expectedUserStateGeneration = previousGeneration
        )

        store.getEnteredIds().shouldBeEmpty()
        store.getActivePolygonIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenRemovalFailedAndOnlyRetainedDefinitionExists_expectDoesNotEvaluateRetiredPolygon() = runTest {
        store.saveCachedRegions(emptyList())
        store.saveRetainedRegisteredRegions(listOf(polygonRegion()))

        engine.processResponsiveLocation(insideFix())

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenCachedRingThatNoLongerValidates_expectPolygonSkippedNotTreatedAsItsCircle() = runTest {
        // Self-intersecting ring: the region still carries a wide enclosing trigger circle, and the fix
        // is well inside that circle. Falling back to it would report ENTER for the wrong area.
        store.saveCachedRegions(
            listOf(
                GeofenceRegion(
                    id = POLYGON_ID,
                    latitude = 37.7750,
                    longitude = -122.4194,
                    radius = 5_000f,
                    polygonVertices = listOf(
                        PolygonCoordinate(37.7745, -122.4200),
                        PolygonCoordinate(37.7755, -122.4188),
                        PolygonCoordinate(37.7745, -122.4188),
                        PolygonCoordinate(37.7755, -122.4200)
                    )
                )
            )
        )
        engine.resetEvidence(POLYGON_ID)

        engine.processResponsiveLocation(insideFix())

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processResponsiveLocation_givenOlderStageStillUnavailable_expectNewEdgeDoesNotOvertakeIt() = runTest {
        coEvery { emitter.recoverPendingTransitions() } returns false

        engine.processResponsiveLocation(insideFix())

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun resetEvidence_givenInactiveEditThenLaterActivation_expectPreActivationFixesRejected() = runTest {
        store.deactivatePolygon(POLYGON_ID)
        engine.stop()
        val beforeActivation = SystemClock.elapsedRealtimeNanos()

        engine.resetEvidence(POLYGON_ID)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        store.activatePolygon(POLYGON_ID)
        engine.activate(POLYGON_ID)
        engine.processResponsiveLocation(insideFix(elapsedRealtimeNanos = beforeActivation))

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ---------- exits ----------

    @Test
    fun processResponsiveLocation_givenCoarseExitThenDecisivePolygonExit_expectSessionTerminates() = runTest {
        store.recordEntered(POLYGON_ID)
        store.recordPolygonCoarseOutside(POLYGON_ID)

        engine.processResponsiveLocation(outsideFix())

        store.getEnteredIds().shouldBeEmpty()
        store.getActivePolygonIds().shouldBeEmpty()
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                any(),
                Event.GeofenceTransition.EXIT,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun processResponsiveLocation_givenFineExitCannotPersist_expectSessionRemainsActiveForRetry() = runTest {
        store.recordEntered(POLYGON_ID)
        store.recordPolygonCoarseOutside(POLYGON_ID)
        coEvery {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns GeofenceTransitionEmitter.Result.PERSIST_FAILED

        engine.processResponsiveLocation(outsideFix())

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getActivePolygonIds() shouldContainSame setOf(POLYGON_ID)
    }

    private fun insideFix(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L,
        timestampMillis: Long = 100_000L,
        accuracyMeters: Float = 5f
    ) = fix(37.7750, -122.4194, elapsedRealtimeNanos, timestampMillis, accuracyMeters)

    private fun outsideFix(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
    ) = fix(37.7750, -122.4175, elapsedRealtimeNanos)

    private fun fix(
        latitude: Double,
        longitude: Double,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L,
        timestampMillis: Long = 100_000L,
        accuracyMeters: Float = 5f
    ) = Location("test").apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = accuracyMeters
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = timestampMillis
    }

    // Roughly 110 m x 105 m, so its centre sits ~52 m from the nearest edge.
    private fun polygonRegion() = GeofenceRegion(
        id = POLYGON_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 200f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    )

    // Roughly 39 m x 35 m — a single retail unit. Centre is under 20 m from the nearest edge.
    private fun smallPolygonRegion() = GeofenceRegion(
        id = SMALL_POLYGON_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 400f,
        polygonVertices = listOf(
            PolygonCoordinate(37.77482, -122.41960),
            PolygonCoordinate(37.77482, -122.41920),
            PolygonCoordinate(37.77517, -122.41920),
            PolygonCoordinate(37.77517, -122.41960)
        )
    )

    private companion object {
        const val POLYGON_ID = "campus"
        const val SMALL_POLYGON_ID = "retail-unit"
    }
}
