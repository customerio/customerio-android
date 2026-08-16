package io.customer.geofence.polygon

import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
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
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonLocationEngineTest : RobolectricTest() {
    private val client: FusedLocationProviderClient = mockk(relaxed = true)
    private val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)
    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var engine: PolygonLocationEngine
    private lateinit var engineTestScheduler: TestCoroutineScheduler

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
        coEvery { emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            GeofenceTransitionEmitter.Result.PERSISTED
        coEvery { emitter.recoverPendingTransitions() } returns true
        engineTestScheduler = TestCoroutineScheduler()
        val engineDispatcher = UnconfinedTestDispatcher(engineTestScheduler)
        engine = PolygonLocationEngine(
            client = client,
            store = store,
            transitionProcessor = GeofenceBusinessTransitionProcessor(
                store,
                secureUserStore,
                emitter,
                logger
            ),
            clock = clock,
            dispatchersProvider = object : DispatchersProvider {
                override val background = engineDispatcher
                override val main = engineDispatcher
                override val default = engineDispatcher
            },
            logger = logger
        )
    }

    @Test
    fun processLocations_givenThreeTimeSeparatedInsideFixes_expectOneCommittedEnter() = runTest {
        engine.processLocations(insideFixes())

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
    fun processLocations_givenDelayedApproachBatch_expectObservedFixCanArmSession() = runTest {
        val base = SystemClock.elapsedRealtimeNanos() - 50_000_000_000L
        every { client.requestLocationUpdates(any(), any<LocationCallback>(), any()) } returns
            Tasks.forResult(null)
        engine.activateFromApproach(POLYGON_ID, base)
        engine.start {}
        shadowOf(Looper.getMainLooper()).idle()

        engine.processLocations(
            listOf(
                location(37.7750, -122.4194, base),
                location(37.7750, -122.4194, base + 2_000_000_000L),
                location(37.7750, -122.4194, base + 4_000_000_000L)
            )
        )

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
    }

    @Test
    fun processLocations_givenBatchFromPreviousUserGeneration_expectDoesNotAffectNewSession() = runTest {
        val previousGeneration = store.userStateGeneration()
        store.beginUserSession("user-2")
        store.saveRegisteredIds(setOf(POLYGON_ID))
        store.saveRoutableRegisteredIds(setOf(POLYGON_ID))
        store.activatePolygon(POLYGON_ID)
        store.recordPolygonCoarseInside(POLYGON_ID)
        every { secureUserStore.getUserId() } returns "user-2"

        engine.processLocations(
            locations = insideFixes(),
            expectedUserStateGeneration = previousGeneration
        )

        store.getEnteredIds().shouldBeEmpty()
        store.getActivePolygonIds() shouldContainSame setOf(POLYGON_ID)
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(
                any(),
                any(),
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
    fun processLocations_givenRemovalFailedAndOnlyRetainedDefinitionExists_expectDoesNotEvaluateRetiredPolygon() = runTest {
        store.saveCachedRegions(emptyList())
        store.saveRetainedRegisteredRegions(listOf(polygonRegion()))

        engine.processLocations(insideFixes())

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processLocations_givenOlderStageStillUnavailable_expectNewEdgeDoesNotOvertakeIt() = runTest {
        coEvery { emitter.recoverPendingTransitions() } returns false

        engine.processLocations(insideFixes())

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processLocations_givenEqualTimestampReplay_expectDoesNotCountAsThreeConfirmations() = runTest {
        val timestamp = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L
        val repeated = List(3) { location(37.7750, -122.4194, timestamp) }

        engine.processLocations(repeated)

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun processLocations_givenFastAmbiguousFixBetweenConfirmations_expectEvidenceResets() = runTest {
        val base = SystemClock.elapsedRealtimeNanos() - 8_000_000_000L

        engine.processLocations(
            listOf(
                location(37.7750, -122.4194, base),
                location(37.7750, -122.4188, base + 500_000_000L),
                location(37.7750, -122.4194, base + 2_000_000_000L),
                location(37.7750, -122.4194, base + 4_000_000_000L)
            )
        )

        store.getEnteredIds().shouldBeEmpty()
        engine.processLocations(listOf(location(37.7750, -122.4194, base + 6_000_000_000L)))
        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
    }

    @Test
    fun processLocations_givenConcurrentCompleteBatches_expectSerializedSingleEnter() = runTest {
        coroutineScope {
            List(8) { async { engine.processLocations(insideFixes()) } }.awaitAll()
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
    fun processLocations_givenCoarseExitThenConfirmedPolygonExit_expectSessionTerminates() = runTest {
        store.recordEntered(POLYGON_ID)
        store.recordPolygonCoarseOutside(POLYGON_ID)

        engine.processLocations(outsideFixes())

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
    fun processLocations_givenFineExitCannotPersist_expectSessionRemainsActiveForRetry() = runTest {
        store.recordEntered(POLYGON_ID)
        store.recordPolygonCoarseOutside(POLYGON_ID)
        coEvery {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns GeofenceTransitionEmitter.Result.PERSIST_FAILED

        engine.processLocations(outsideFixes())

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getActivePolygonIds() shouldContainSame setOf(POLYGON_ID)
    }

    @Test
    fun start_givenStopBeforeFusedRegistrationCompletes_expectLateCallbackRemoved() {
        val callback = slot<LocationCallback>()
        val registration = TaskCompletionSource<Void>()
        every { client.requestLocationUpdates(any(), capture(callback), any()) } returns registration.task

        engine.start {}
        engine.stop()
        registration.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { client.removeLocationUpdates(callback.captured) }
    }

    @Test
    fun stop_givenFirstFusedRemovalFails_expectSameCallbackRetriedUntilRemoved() = runTest {
        val callback = slot<LocationCallback>()
        var removalAttempts = 0
        every {
            client.requestLocationUpdates(any(), capture(callback), any())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } answers {
            removalAttempts += 1
            if (removalAttempts == 1) {
                Tasks.forException(IllegalStateException("transient removal failure"))
            } else {
                Tasks.forResult(null)
            }
        }

        engine.start {}
        shadowOf(Looper.getMainLooper()).idle()
        engine.stop()
        shadowOf(Looper.getMainLooper()).idle()
        engineTestScheduler.advanceTimeBy(1_000L)
        engineTestScheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 2) { client.removeLocationUpdates(callback.captured) }
    }

    @Test
    fun stopIfCurrent_givenOlderServiceIsDestroyed_expectNewRegistrationSurvives() {
        val callbacks = mutableListOf<LocationCallback>()
        every { client.requestLocationUpdates(any(), capture(callbacks), any()) } returns
            Tasks.forResult(null)

        val previousGeneration = checkNotNull(engine.start {})
        engine.stop()
        engine.start {}

        engine.stopIfCurrent(previousGeneration)

        callbacks.size shouldBeEqualTo 2
        verify(exactly = 1) { client.removeLocationUpdates(callbacks[0]) }
        verify(exactly = 0) { client.removeLocationUpdates(callbacks[1]) }
    }

    @Test
    fun start_givenOldRegistrationFailsAfterNewSessionStarts_expectNewSessionSurvives() {
        val firstRegistration = TaskCompletionSource<Void>()
        val secondRegistration = TaskCompletionSource<Void>()
        var requests = 0
        every { client.requestLocationUpdates(any(), any<LocationCallback>(), any()) } answers {
            if (requests++ == 0) firstRegistration.task else secondRegistration.task
        }
        val onUnavailable: () -> Unit = mockk(relaxed = true)

        engine.start(onUnavailable)
        engine.stop()
        engine.start(onUnavailable)
        firstRegistration.setException(SecurityException("old request denied"))
        engine.start(onUnavailable)

        verify(exactly = 2) { client.requestLocationUpdates(any(), any<LocationCallback>(), any()) }
        verify(exactly = 0) { onUnavailable.invoke() }
    }

    @Test
    fun start_givenSamplingModeChanges_expectReusesCallbackWithoutRemoveAddGap() = runTest {
        val callbacks = mutableListOf<LocationCallback>()
        every { client.requestLocationUpdates(any(), capture(callbacks), any()) } answers {
            Tasks.forResult(null)
        }

        engine.start {}
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        callbacks.single().onLocationResult(
            LocationResult.create(
                listOf(
                    location(
                        latitude = 37.80,
                        longitude = -122.45,
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    )
                )
            )
        )
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        callbacks.size shouldBeEqualTo 2
        check(callbacks[0] === callbacks[1])
        verify(exactly = 0) { client.removeLocationUpdates(any<LocationCallback>()) }
    }

    @Test
    fun start_givenTransientRegistrationFailure_expectAutonomousRetry() = runTest {
        var requestCount = 0
        every { client.requestLocationUpdates(any(), any<LocationCallback>(), any()) } answers {
            requestCount += 1
            if (requestCount == 1) {
                Tasks.forException(IllegalStateException("temporarily unavailable"))
            } else {
                Tasks.forResult(null)
            }
        }

        engine.start {}
        shadowOf(Looper.getMainLooper()).idle()
        engineTestScheduler.advanceTimeBy(5_000L)
        engineTestScheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 2) { client.requestLocationUpdates(any(), any<LocationCallback>(), any()) }
    }

    @Test
    fun start_givenAcceptedRequestButNoFixes_expectWatchdogReRegisters() = runTest {
        every {
            client.requestLocationUpdates(any(), any<LocationCallback>(), any())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)

        engine.start {}
        shadowOf(Looper.getMainLooper()).idle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(91))
        engineTestScheduler.advanceTimeBy(90_000L)
        engineTestScheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 2) { client.requestLocationUpdates(any(), any<LocationCallback>(), any()) }
        verify(exactly = 0) { client.removeLocationUpdates(any<LocationCallback>()) }
    }

    @Test
    fun start_givenStationaryFarFromBoundaryAfterBurst_expectBalancedThenHighWhenMovingNearBoundary() = runTest {
        val requests = mutableListOf<LocationRequest>()
        val callback = slot<LocationCallback>()
        every {
            client.requestLocationUpdates(capture(requests), capture(callback), any())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)

        engine.start {}
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        val far = location(
            latitude = 37.80,
            longitude = -122.45,
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        )
        callback.captured.onLocationResult(LocationResult.create(listOf(far)))
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        requests.map(LocationRequest::getPriority) shouldBeEqualTo listOf(
            Priority.PRIORITY_HIGH_ACCURACY,
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        )

        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        val movingNearBoundary = location(
            latitude = 37.7750,
            longitude = -122.4186,
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        ).apply { speed = 2f }
        callback.captured.onLocationResult(LocationResult.create(listOf(movingNearBoundary)))
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        requests.map(LocationRequest::getPriority) shouldBeEqualTo listOf(
            Priority.PRIORITY_HIGH_ACCURACY,
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            Priority.PRIORITY_HIGH_ACCURACY
        )
    }

    @Test
    fun start_givenStationaryNearBoundaryAfterBurst_expectRemainsHighAccuracy() = runTest {
        val requests = mutableListOf<LocationRequest>()
        val callback = slot<LocationCallback>()
        every {
            client.requestLocationUpdates(capture(requests), capture(callback), any())
        } returns Tasks.forResult(null)

        engine.start {}
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        callback.captured.onLocationResult(
            LocationResult.create(
                listOf(
                    location(
                        latitude = 37.7750,
                        longitude = -122.4187,
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    )
                )
            )
        )
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        requests.map(LocationRequest::getPriority) shouldBeEqualTo listOf(
            Priority.PRIORITY_HIGH_ACCURACY
        )
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
        engine.processLocations(
            listOf(
                location(37.7750, -122.4194, beforeActivation),
                location(37.7750, -122.4194, beforeActivation + 2_000_000_000L),
                location(37.7750, -122.4194, beforeActivation + 4_000_000_000L)
            )
        )

        store.getEnteredIds().shouldBeEmpty()
        coVerify(exactly = 0) {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun insideFixes(): List<Location> {
        val base = SystemClock.elapsedRealtimeNanos() - 6_000_000_000L
        return listOf(
            location(37.7750, -122.4194, base),
            location(37.7750, -122.4194, base + 2_000_000_000L),
            location(37.7750, -122.4194, base + 4_000_000_000L)
        )
    }

    private fun outsideFixes(): List<Location> {
        val base = SystemClock.elapsedRealtimeNanos() - 6_000_000_000L
        return listOf(
            location(37.7750, -122.4175, base),
            location(37.7750, -122.4175, base + 2_000_000_000L),
            location(37.7750, -122.4175, base + 4_000_000_000L)
        )
    }

    private fun location(latitude: Double, longitude: Double, elapsedRealtimeNanos: Long) =
        Location("test").apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 5f
            this.elapsedRealtimeNanos = elapsedRealtimeNanos
            time = System.currentTimeMillis()
        }

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

    private companion object {
        const val POLYGON_ID = "campus"
    }
}
