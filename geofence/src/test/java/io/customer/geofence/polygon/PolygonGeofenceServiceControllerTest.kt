package io.customer.geofence.polygon

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.PolygonTrackingMode
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class PolygonGeofenceServiceControllerTest {
    private val context: Context = mockk(relaxed = true)
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val engine: PolygonLocationEngine = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val controller = PolygonGeofenceServiceController(
        context,
        store,
        engine,
        approachMonitor,
        secureUserStore,
        logger
    )

    @Before
    fun setUp() {
        every { store.userStateGeneration() } returns 0L
        every { store.hasActiveUserSession() } returns true
        every { store.activeUserSessionId() } returns "user-1"
        every { secureUserStore.getUserId() } returns "user-1"
        every { store.getRegisteredIds() } returns setOf("campus")
        every { store.getRoutableRegisteredIds() } returns setOf("campus")
        every { store.getCachedRegion("campus") } returns polygonRegion()
        every { store.getActivePolygonIds() } returns emptySet()
        every { store.getEnteredIds() } returns emptySet()
        every { store.getPolygonTrackingMode() } returns PolygonTrackingMode.CONTINUOUS
    }

    @Test
    fun activate_givenDefaultResponsiveMode_expectEvaluatesWithoutForegroundService() {
        every { store.getPolygonTrackingMode() } returns PolygonTrackingMode.RESPONSIVE

        controller.activate("campus")

        verifyOrder {
            store.recordPolygonCoarseInside("campus")
            store.activatePolygon("campus")
            engine.activate("campus")
        }
        verify(exactly = 0) { context.startForegroundService(any<Intent>()) }
    }

    @Test
    fun activate_givenNewPolygon_expectDurableStateBeforeEngineAndServiceStart() {
        controller.activate("campus")

        verifyOrder {
            store.recordPolygonCoarseInside("campus")
            store.activatePolygon("campus")
            engine.activate("campus")
            context.startForegroundService(any<Intent>())
        }
    }

    @Test
    fun activate_givenContinuousServiceNotDeclaredByHost_expectFailsClosedWithoutCrash() {
        every { context.startForegroundService(any()) } throws
            ActivityNotFoundException("continuous service not declared")

        controller.activate("campus")

        verify { logger.logPolygonMonitoringFailed("continuous service not declared") }
    }

    @Test
    fun deactivate_givenForegroundPromotionPending_expectServiceStopsOnlyAfterPromotion() {
        controller.activate("campus")

        controller.deactivate("campus")

        verify(exactly = 0) { context.stopService(any()) }

        controller.onServicePromoted(1L)
        controller.deactivate("campus")

        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun deactivate_givenOlderServiceIsDestroyedDuringNewPromotion_expectNewPromotionRemainsProtected() {
        controller.activate("campus")
        controller.onServicePromoted(1L)
        controller.deactivate("campus")
        controller.activate("campus")

        controller.onServiceDestroyed(1L)
        controller.deactivate("campus")

        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun activate_givenServiceNeverAcknowledgesPromotion_expectOneBoundedRetryThenGateReopens() {
        every { store.getActivePolygonIds() } returns setOf("campus")

        controller.activate("campus")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        verify(exactly = 2) { context.startForegroundService(any<Intent>()) }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        verify(exactly = 2) { context.startForegroundService(any<Intent>()) }

        every { store.getActivePolygonIds() } returns emptySet()
        controller.deactivate("campus")
        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun onCoarseExit_givenPolygonNotCommittedInside_expectProcessesFixThenStopsSession() = runTest {
        val location = location()

        controller.onCoarseExit("campus", location)

        verifyOrder {
            store.recordPolygonCoarseOutside("campus")
            store.getEnteredIds()
            store.deactivatePolygon("campus")
            engine.deactivate("campus")
            store.getActivePolygonIds()
            context.stopService(any())
        }
        coVerify { engine.processLocation(location, 0L) }
    }

    @Test
    fun onCoarseExit_givenPolygonCommittedInside_expectKeepsSamplingUntilFineExit() = runTest {
        every { store.getEnteredIds() } returns setOf("campus")
        val location = location()

        controller.onCoarseExit("campus", location)

        coVerify { engine.processLocation(location, 0L) }
        verify { store.activatePolygon("campus") }
        verify { context.startForegroundService(any()) }
        verify(exactly = 0) { store.deactivatePolygon(any()) }
        verify(exactly = 0) { engine.deactivate(any()) }
    }

    @Test
    fun onCoarseExit_givenNewerActivationWhileFixIsProcessing_expectDoesNotDeactivateNewSession() = runTest {
        var coarseInside = emptySet<String>()
        every { store.getCoarseInsidePolygonIds() } answers { coarseInside }
        every { store.recordPolygonCoarseOutside(any()) } answers {
            coarseInside = coarseInside - firstArg<String>()
        }
        every { store.recordPolygonCoarseInside(any()) } answers {
            coarseInside = coarseInside + firstArg<String>()
        }
        val processingStarted = CompletableDeferred<Unit>()
        val finishProcessing = CompletableDeferred<Unit>()
        val exitLocation = location(elapsedRealtimeNanos = 100L)
        io.mockk.coEvery { engine.processLocation(exitLocation, any()) } coAnswers {
            processingStarted.complete(Unit)
            finishProcessing.await()
        }

        val exit = async { controller.onCoarseExit("campus", exitLocation) }
        processingStarted.await()
        controller.activate("campus")
        finishProcessing.complete(Unit)
        exit.await()

        verify(exactly = 0) { store.deactivatePolygon("campus") }
        verify(exactly = 0) { engine.deactivate("campus") }
    }

    @Test
    fun onCoarseExit_givenOlderTriggeringFixAfterNewerEnter_expectIgnored() = runTest {
        controller.activate("campus", location(elapsedRealtimeNanos = 200L))

        controller.onCoarseExit("campus", location(elapsedRealtimeNanos = 100L))

        verify(exactly = 0) { store.recordPolygonCoarseOutside("campus") }
        verify(exactly = 0) { store.deactivatePolygon("campus") }
    }

    @Test
    fun activate_givenQueuedCallbackFromBeforeUserReset_expectDoesNotRestartMonitoring() = runTest {
        var generation = 0L
        var registeredIds = setOf("campus")
        every { store.userStateGeneration() } answers { generation }
        every { store.getRegisteredIds() } answers { registeredIds }
        every { store.clearUserScopedState() } answers {
            generation += 1
            registeredIds = emptySet()
        }

        controller.clearUserScopedState()
        controller.activate(
            polygonId = "campus",
            triggeringLocation = location(),
            expectedUserStateGeneration = 0L
        )

        verify { store.clearUserScopedState() }
        verify(exactly = 0) { store.recordPolygonCoarseInside(any()) }
        verify(exactly = 0) { store.activatePolygon(any()) }
        verify(exactly = 0) { engine.activate(any()) }
        verify(exactly = 0) { context.startForegroundService(any<Intent>()) }
    }

    @Test
    fun activate_givenPolygonWasReplacedByCircle_expectDoesNotStartFineMonitoring() = runTest {
        val oldPolygonRevision = polygonRegion().transitionRevision()
        every { store.getCachedRegion("campus") } returns GeofenceRegion(
            id = "campus",
            latitude = 37.775,
            longitude = -122.4194,
            radius = 100f
        )

        controller.activate(
            polygonId = "campus",
            triggeringLocation = location(),
            expectedUserStateGeneration = 0L,
            expectedRegionRevision = oldPolygonRevision
        )

        verify(exactly = 0) { store.activatePolygon(any()) }
        verify(exactly = 0) { engine.activate(any()) }
        verify(exactly = 0) { context.startForegroundService(any<Intent>()) }
    }

    @Test
    fun recover_givenPersistedActiveSession_expectRestartsForegroundService() {
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { store.getLastRegistrationUptime() } returns 0L

        controller.recover()

        verify { context.startForegroundService(any()) }
    }

    @Test
    fun recover_givenResponsiveMode_expectRestoresPassiveMonitorWithoutForegroundService() {
        every { store.getPolygonTrackingMode() } returns PolygonTrackingMode.RESPONSIVE
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { store.getLastRegistrationUptime() } returns 0L

        controller.recover()

        verify { approachMonitor.start(0L) }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun recover_givenPersistedSessionFromBeforeReboot_expectClearsInsteadOfRestarting() {
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { store.getLastRegistrationUptime() } returns Long.MAX_VALUE

        controller.recover()

        verify { store.clearActivePolygonIds() }
        verify { store.retainCoarseInsidePolygonIds(emptySet()) }
        verify { engine.stop() }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun invalidatePersistedCoarseState_givenReboot_expectStopsOldSessionAndClearsMembership() {
        controller.invalidatePersistedCoarseState()

        verifyOrder {
            store.clearActivePolygonIds()
            store.retainCoarseInsidePolygonIds(emptySet())
            engine.stop()
            context.stopService(any())
        }
    }

    @Test
    fun invalidateOsRegistrationState_givenGmsUnavailable_expectForcesFutureReregistrationAndStopsFineSession() {
        controller.invalidateOsRegistrationState()

        verifyOrder {
            store.saveRegisteredIds(emptySet())
            store.saveRoutableRegisteredIds(emptySet())
            store.saveRetainedRegisteredRegions(emptyList())
            store.clearActivePolygonIds()
            store.retainCoarseInsidePolygonIds(emptySet())
            engine.stop()
            context.stopService(any())
        }
    }

    @Test
    fun clearUserSessionRetainingOsRegistrations_givenOsRemovalFailed_expectStopsFineMonitoring() {
        controller.clearUserSessionRetainingOsRegistrations()

        verifyOrder {
            store.clearUserSessionRetainingOsRegistrations()
            engine.stop()
            context.stopService(any())
        }
        verify(exactly = 0) { store.saveRegisteredIds(any()) }
    }

    @Test
    fun activate_givenNextUserBeforeCleanupRetry_expectCleanupOnlyPolygonCannotRestartMonitoring() = runTest {
        var generation = 1L
        var sessionOwner: String? = "user-A"
        var currentUser: String? = "user-A"
        var routableIds = setOf("campus")
        every { store.userStateGeneration() } answers { generation }
        every { store.activeUserSessionId() } answers { sessionOwner }
        every { secureUserStore.getUserId() } answers { currentUser }
        every { store.getRoutableRegisteredIds() } answers { routableIds }
        every { store.clearUserSessionRetainingOsRegistrations() } answers {
            generation += 1L
            sessionOwner = null
            routableIds = emptySet()
        }

        controller.clearUserSessionRetainingOsRegistrations()
        sessionOwner = "user-B"
        currentUser = "user-B" // identified before its registration refresh completes
        controller.activate(
            polygonId = "campus",
            triggeringLocation = location(),
            expectedUserStateGeneration = generation,
            expectedRegionRevision = polygonRegion().transitionRevision()
        )

        verify(exactly = 0) { store.activatePolygon(any()) }
        verify(exactly = 0) { engine.activate(any()) }
        verify(exactly = 0) { context.startForegroundService(any<Intent>()) }
    }

    @Test
    fun recover_givenNoIdentifiedUser_expectClearsPersistedFineSessionWithoutRestarting() {
        every { secureUserStore.getUserId() } returns null
        every { store.getActivePolygonIds() } returns setOf("campus")

        controller.recover()

        verify { store.clearActivePolygonIds() }
        verify { engine.stop() }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun startEngineForService_givenPersistedSessionButNoIdentifiedUser_expectClearsWithoutSampling() {
        every { secureUserStore.getUserId() } returns null
        every { store.getActivePolygonIds() } returns setOf("campus")

        val started = controller.startEngineForService {}

        started shouldBeEqualTo null
        verify { store.clearActivePolygonIds() }
        verify { store.retainCoarseInsidePolygonIds(emptySet()) }
        verify { engine.stop() }
        verify(exactly = 0) { engine.start(any()) }
    }

    @Test
    fun startEngineForService_givenMatchingIdentifiedRoutableSession_expectStartsSampling() {
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { engine.start(any()) } returns 7L

        val started = controller.startEngineForService {}

        started shouldBeEqualTo 7L
        verify { engine.start(any()) }
    }

    @Test
    fun startEngineForService_givenResponsiveMode_expectRejectsStickyServiceWithoutClearingPassiveState() {
        every { store.getPolygonTrackingMode() } returns PolygonTrackingMode.RESPONSIVE
        every { store.getActivePolygonIds() } returns setOf("campus")

        val started = controller.startEngineForService {}

        started shouldBeEqualTo null
        verify { engine.stop() }
        verify(exactly = 0) { engine.start(any()) }
        verify(exactly = 0) { store.clearActivePolygonIds() }
        verify(exactly = 0) { store.retainCoarseInsidePolygonIds(any()) }
    }

    @Test
    fun setTrackingMode_givenContinuousSessionChangedToResponsive_expectPersistsAndStopsService() {
        every { store.getPolygonTrackingMode() } returns PolygonTrackingMode.CONTINUOUS

        controller.setTrackingMode(PolygonTrackingMode.RESPONSIVE)

        verifyOrder {
            store.savePolygonTrackingMode(PolygonTrackingMode.RESPONSIVE)
            engine.stop()
            context.stopService(any())
        }
    }

    @Test
    fun setTrackingMode_givenResponsiveActiveSessionChangedToContinuous_expectStartsService() {
        var trackingMode = PolygonTrackingMode.RESPONSIVE
        every { store.getPolygonTrackingMode() } answers { trackingMode }
        every { store.savePolygonTrackingMode(any()) } answers {
            trackingMode = firstArg()
        }
        every { store.getActivePolygonIds() } returns setOf("campus")

        controller.setTrackingMode(PolygonTrackingMode.CONTINUOUS)

        verify { store.savePolygonTrackingMode(PolygonTrackingMode.CONTINUOUS) }
        verify { context.startForegroundService(any()) }
    }

    @Test
    fun beginUserSession_givenDifferentUser_expectStopsOldFineSessionBeforeRefresh() {
        every { store.activeUserSessionId() } returns "user-A"

        controller.beginUserSession("user-B")

        verifyOrder {
            store.beginUserSession("user-B")
            engine.stop()
            context.stopService(any())
        }
    }

    @Test
    fun reconcileRegisteredPolygons_givenPolygon_expectStartsResponsiveApproachMonitoring() {
        controller.reconcileRegisteredPolygons(setOf("campus"))

        verify { approachMonitor.start(0L) }
    }

    @Test
    fun processApproachLocations_givenFixInsideTrigger_expectStartsFineEvaluationWithoutClaimingOsCircle() = runTest {
        var activeIds = emptySet<String>()
        val operations = mutableListOf<String>()
        every { store.getActivePolygonIds() } answers { activeIds }
        every { store.activatePolygon(any()) } answers { activeIds = activeIds + firstArg<String>() }
        coEvery { engine.processLocation(any(), 0L) } answers { operations += "process" }
        every { context.startForegroundService(any<Intent>()) } answers {
            operations += "start-service"
            null
        }

        val accepted = controller.processApproachLocations(
            locations = listOf(location(elapsedRealtimeNanos = 100L)),
            expectedUserStateGeneration = 0L
        )

        accepted shouldBeEqualTo true
        verify { store.activatePolygon("campus") }
        verify { engine.activateFromApproach("campus", 100L) }
        coVerify { engine.processLocation(any(), 0L) }
        verify { context.startForegroundService(any<Intent>()) }
        verify(exactly = 0) { store.recordPolygonCoarseInside(any()) }
        operations shouldBeEqualTo listOf("process", "start-service")
    }

    @Test
    fun processApproachLocations_givenResponsiveMode_expectEvaluatesBatchWithoutForegroundService() = runTest {
        every { store.getPolygonTrackingMode() } returns PolygonTrackingMode.RESPONSIVE
        var activeIds = emptySet<String>()
        every { store.getActivePolygonIds() } answers { activeIds }
        every { store.activatePolygon(any()) } answers { activeIds = activeIds + firstArg<String>() }

        controller.processApproachLocations(
            locations = listOf(location(elapsedRealtimeNanos = 100L)),
            expectedUserStateGeneration = 0L
        )

        verify { engine.activateFromApproach("campus", 100L) }
        coVerify { engine.processLocation(any(), 0L) }
        verify(exactly = 0) { context.startForegroundService(any<Intent>()) }
    }

    @Test
    fun processApproachLocations_givenApproachSessionMovesOutsideTrigger_expectStopsIt() = runTest {
        var activeIds = setOf("campus")
        every { store.getActivePolygonIds() } answers { activeIds }
        every { store.deactivatePolygon(any()) } answers { activeIds = activeIds - firstArg<String>() }
        val outside = location(elapsedRealtimeNanos = 100L).apply {
            latitude = 38.0
            longitude = -122.0
        }

        val accepted = controller.processApproachLocations(
            locations = listOf(outside),
            expectedUserStateGeneration = 0L
        )

        accepted shouldBeEqualTo true
        verify { store.deactivatePolygon("campus") }
        verify { engine.deactivate("campus") }
        verify { context.stopService(any()) }
    }

    @Test
    fun processApproachLocations_givenUncertainFixAtTrigger_expectDoesNotStartFineSession() = runTest {
        val uncertain = location(elapsedRealtimeNanos = 100L).apply { accuracy = 500f }

        controller.processApproachLocations(listOf(uncertain), 0L)

        verify(exactly = 0) { store.activatePolygon(any()) }
        verify(exactly = 0) { engine.activate(any()) }
        coVerify(exactly = 0) { engine.processLocation(any(), any()) }
        verify(exactly = 0) { context.startForegroundService(any<Intent>()) }
    }

    @Test
    fun processApproachLocations_givenNoActiveOrNearbyPolygon_expectSkipsFineEvaluator() = runTest {
        val farAway = location(elapsedRealtimeNanos = 100L).apply {
            latitude = 38.0
            longitude = -122.0
        }

        controller.processApproachLocations(listOf(farAway), 0L)

        coVerify(exactly = 0) { engine.processLocation(any(), any()) }
    }

    @Test
    fun processApproachLocations_givenOsCircleStillInside_expectDoesNotStopSession() = runTest {
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { store.getCoarseInsidePolygonIds() } returns setOf("campus")
        val outside = location(elapsedRealtimeNanos = 100L).apply {
            latitude = 38.0
            longitude = -122.0
        }

        controller.processApproachLocations(listOf(outside), 0L)

        verify(exactly = 0) { store.deactivatePolygon("campus") }
        verify(exactly = 0) { engine.deactivate("campus") }
    }

    @Test
    fun processApproachLocations_givenPreviousUserGeneration_expectRejectsWithoutLocationWork() = runTest {
        every { store.userStateGeneration() } returns 1L

        val accepted = controller.processApproachLocations(
            locations = listOf(location(elapsedRealtimeNanos = 100L)),
            expectedUserStateGeneration = 0L
        )

        accepted shouldBeEqualTo false
        verify(exactly = 0) { store.activatePolygon(any()) }
        coVerify(exactly = 0) { engine.processLocation(any(), any()) }
    }

    private fun location(elapsedRealtimeNanos: Long = 0L) = Location("test").apply {
        latitude = 37.775
        longitude = -122.4194
        accuracy = 5f
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
    }

    private fun polygonRegion() = GeofenceRegion(
        id = "campus",
        latitude = 37.775,
        longitude = -122.4194,
        radius = 200f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    )
}
