package io.customer.geofence.polygon

import android.content.Context
import android.content.Intent
import android.location.Location
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
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
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)
    private val controller = PolygonGeofenceServiceController(context, store, engine, secureUserStore, logger)

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

        started shouldBeEqualTo false
        verify { store.clearActivePolygonIds() }
        verify { store.retainCoarseInsidePolygonIds(emptySet()) }
        verify { engine.stop() }
        verify(exactly = 0) { engine.start(any()) }
    }

    @Test
    fun startEngineForService_givenMatchingIdentifiedRoutableSession_expectStartsSampling() {
        every { store.getActivePolygonIds() } returns setOf("campus")

        val started = controller.startEngineForService {}

        started shouldBeEqualTo true
        verify { engine.start(any()) }
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
