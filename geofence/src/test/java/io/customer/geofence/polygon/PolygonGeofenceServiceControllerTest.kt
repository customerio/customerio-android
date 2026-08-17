package io.customer.geofence.polygon

import android.content.Context
import android.location.Location
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class PolygonGeofenceServiceControllerTest {
    private val context: Context = mockk(relaxed = true)
    private val store: GeofenceRegionStore = mockk(relaxed = true)
    private val engine: PolygonLocationEngine = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val controller = PolygonGeofenceServiceController(
        context,
        store,
        engine,
        approachMonitor,
        secureUserStore
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
        every { store.getCachedRegions() } returns listOf(polygonRegion())
        every { store.getActivePolygonIds() } returns emptySet()
        every { store.getEnteredIds() } returns emptySet()
    }

    @Test
    fun activate_givenNewPolygon_expectPersistsStateBeforeEvaluation() {
        controller.activate("campus")

        verifyOrder {
            store.recordPolygonCoarseInside("campus")
            store.activatePolygon("campus")
            engine.activate("campus")
        }
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
            engine.stop()
        }
        coVerify { engine.processResponsiveLocation(location, 0L) }
    }

    @Test
    fun onCoarseExit_givenPolygonCommittedInside_expectKeepsSamplingUntilFineExit() = runTest {
        every { store.getEnteredIds() } returns setOf("campus")
        val location = location()

        controller.onCoarseExit("campus", location)

        coVerify { engine.processResponsiveLocation(location, 0L) }
        verify { store.activatePolygon("campus") }
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
        io.mockk.coEvery { engine.processResponsiveLocation(exitLocation, any()) } coAnswers {
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
    }

    @Test
    fun recover_givenPersistedSession_expectRestoresPassiveMonitor() {
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { store.getLastRegistrationUptime() } returns 0L

        controller.recover()

        verify { approachMonitor.start(0L) }
    }

    @Test
    fun recover_givenPersistedSessionFromBeforeReboot_expectClearsInsteadOfRestarting() {
        every { store.getActivePolygonIds() } returns setOf("campus")
        every { store.getLastRegistrationUptime() } returns Long.MAX_VALUE

        controller.recover()

        verify { store.clearActivePolygonIds() }
        verify { store.retainCoarseInsidePolygonIds(emptySet()) }
        verify { engine.stop() }
    }

    @Test
    fun invalidatePersistedCoarseState_givenReboot_expectStopsOldSessionAndClearsMembership() {
        controller.invalidatePersistedCoarseState()

        verifyOrder {
            store.clearActivePolygonIds()
            store.retainCoarseInsidePolygonIds(emptySet())
            engine.stop()
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
        }
    }

    @Test
    fun clearUserSessionRetainingOsRegistrations_givenOsRemovalFailed_expectStopsFineMonitoring() {
        controller.clearUserSessionRetainingOsRegistrations()

        verifyOrder {
            store.clearUserSessionRetainingOsRegistrations()
            engine.stop()
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
    }

    @Test
    fun recover_givenNoIdentifiedUser_expectClearsPersistedFineSessionWithoutRestarting() {
        every { secureUserStore.getUserId() } returns null
        every { store.getActivePolygonIds() } returns setOf("campus")

        controller.recover()

        verify { store.clearActivePolygonIds() }
        verify { engine.stop() }
    }

    @Test
    fun beginUserSession_givenDifferentUser_expectStopsOldFineSessionBeforeRefresh() {
        every { store.activeUserSessionId() } returns "user-A"

        controller.beginUserSession("user-B")

        verifyOrder {
            store.beginUserSession("user-B")
            engine.stop()
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
        every { store.getActivePolygonIds() } answers { activeIds }
        every { store.activatePolygon(any()) } answers { activeIds = activeIds + firstArg<String>() }

        val accepted = controller.processApproachLocations(
            locations = listOf(location(elapsedRealtimeNanos = 100L)),
            expectedUserStateGeneration = 0L
        )

        accepted shouldBeEqualTo true
        verify { store.activatePolygon("campus") }
        verify { engine.activateFromApproach("campus", 100L) }
        coVerify { engine.processResponsiveLocation(any(), 0L) }
        verify(exactly = 0) { store.recordPolygonCoarseInside(any()) }
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
    }

    @Test
    fun processApproachLocations_givenUncertainFixAtTrigger_expectDoesNotStartFineSession() = runTest {
        val uncertain = location(elapsedRealtimeNanos = 100L).apply { accuracy = 500f }

        controller.processApproachLocations(listOf(uncertain), 0L)

        verify(exactly = 0) { store.activatePolygon(any()) }
        verify(exactly = 0) { engine.activate(any()) }
        coVerify(exactly = 0) { engine.processResponsiveLocation(any(), any()) }
    }

    @Test
    fun processApproachLocations_givenNoActiveOrNearbyPolygon_expectSkipsFineEvaluator() = runTest {
        val farAway = location(elapsedRealtimeNanos = 100L).apply {
            latitude = 38.0
            longitude = -122.0
        }

        controller.processApproachLocations(listOf(farAway), 0L)

        coVerify(exactly = 0) { engine.processResponsiveLocation(any(), any()) }
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
        coVerify(exactly = 0) { engine.processResponsiveLocation(any(), any()) }
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
