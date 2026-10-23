package io.customer.geofence.polygon

import android.app.PendingIntent
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The passive listener guarantees no deliveries, so the tests pin its lifetime, that it never
 * turns a sensor on, and that its fixes take the ordinary coarse-ENTER path.
 */
@RunWith(RobolectricTestRunner::class)
class PolygonPassiveTest : RobolectricTest() {
    private val mockStore: GeofenceRegionStore = mockk(relaxed = true)
    private val mockController: PolygonGeofenceServiceController = mockk(relaxed = true)
    private val mockSecureUserStore: SecureUserStore = mockk(relaxed = true)
    private val recordingPassiveMonitor = RecordingPassiveMonitor()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
                diGraph {
                    android {
                        overrideDependency<GeofenceRegionStore>(mockStore)
                        overrideDependency<PolygonGeofenceServiceController>(mockController)
                        overrideDependency<SecureUserStore>(mockSecureUserStore)
                        overrideDependency<PolygonPassiveMonitor>(recordingPassiveMonitor)
                    }
                }
            }
        )
        every { mockSecureUserStore.getUserId() } returns "user-1"
        every { mockStore.userStateGeneration() } returns 7L
    }

    @Test
    fun request_expectPassivePriorityAndAThrottle() {
        val request = GmsPolygonPassiveMonitor.passiveRequest()

        // PASSIVE is the entire cost argument: anything else turns a sensor on.
        request.priority shouldBeEqualTo Priority.PRIORITY_PASSIVE
        // Without a floor, a navigating maps app would feed the evaluator a fix a second.
        request.minUpdateIntervalMillis shouldBeEqualTo GmsPolygonPassiveMonitor.MINIMUM_INTERVAL_MS
    }

    @Test
    fun reconcile_expectTheListenerFollowsRegistrationExactly() {
        val passive = RecordingPassiveMonitor()
        every { mockStore.getActivePolygonIds() } returns emptySet()
        val controller = controller(passive)

        controller.reconcileRegisteredPolygons(setOf("venue"))
        controller.reconcileRegisteredPolygons(emptySet())

        // Order, not counts: a count assertion would pass for either ordering.
        passive.calls shouldBeEqualTo listOf("start", "stop")
    }

    @Test
    fun receiver_givenTheRegistrationWasStopped_expectNoActivation() = runTest {
        // A fix the OS dispatched before teardown still arrives afterwards. Teardown keeps the
        // routable ids and user generation, so only the cancelled registration can refuse it.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        recordingPassiveMonitor.stop()

        PolygonPassiveReceiver().handleFix(fixAt(VENUE_LAT, VENUE_LNG))

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun receiver_givenTheFixIsInsideTheWakeCircle_expectTheOrdinaryCoarseEnterPath() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        val fix = fixAt(VENUE_LAT, VENUE_LNG)

        PolygonPassiveReceiver().handleFix(fix)

        coVerify {
            mockController.activate(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = 7L,
                expectedRegionRevision = null,
                expectedTeardownGeneration = any()
            )
        }
    }

    @Test
    fun receiver_givenTheFixIsOutsideEveryWakeCircle_expectNoActivation() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())

        PolygonPassiveReceiver().handleFix(fixAt(VENUE_LAT + 1.0, VENUE_LNG))

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun receiver_givenNothingRegistered_expectTheListenerRetiresItself() = runTest {
        // A request left live with GMS (for example across process death) must retire itself
        // rather than wake the process for every other app's fix.
        every { mockStore.getRoutableRegisteredIds() } returns emptySet()
        every { mockStore.getCachedRegions() } returns emptyList()

        PolygonPassiveReceiver().handleFix(fixAt(VENUE_LAT, VENUE_LNG))

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
        recordingPassiveMonitor.calls shouldBeEqualTo listOf("stop")
    }

    @Test
    fun receiver_givenAPolygonIsStillRegistered_expectTheListenerIsLeftAlone() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())

        PolygonPassiveReceiver().handleFix(fixAt(VENUE_LAT, VENUE_LNG))

        recordingPassiveMonitor.calls shouldBeEqualTo emptyList()
    }

    @Test
    fun receiver_givenANonPolygonIsRegistered_expectItIsNotTreatedAsOne() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf("circle")
        every { mockStore.getCachedRegions() } returns listOf(
            venueRegion().copy(id = "circle", polygonVertices = null)
        )

        PolygonPassiveReceiver().handleFix(fixAt(VENUE_LAT, VENUE_LNG))

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun receiver_givenABatch_expectTheNewestFixIsChosen() {
        // Older fixes in a batch describe the way in, not where the device is now, and each extra
        // one would buy another evaluation of the same arrival. Order in the list is not trusted.
        val oldest = fixAt(VENUE_LAT, VENUE_LNG, elapsedRealtimeNanos = 1_000_000_000L)
        val newest = fixAt(VENUE_LAT, VENUE_LNG, elapsedRealtimeNanos = 9_000_000_000L)
        val middle = fixAt(VENUE_LAT, VENUE_LNG, elapsedRealtimeNanos = 5_000_000_000L)

        val chosen = PolygonPassiveReceiver().newestFix(listOf(newest, oldest, middle))

        chosen shouldBeEqualTo newest
        PolygonPassiveReceiver().newestFix(emptyList()) shouldBeEqualTo null
    }

    @Test
    fun receiver_expectAdmissionIsPinnedToTheWakeCircleRadius() = runTest {
        // The venue circle is 120 m, so 110 m admits and 130 m does not.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns emptySet()
        val justInside = fixNorthOfVenue(110.0, accuracyMeters = 1.0f)
        val justOutside = fixNorthOfVenue(130.0, accuracyMeters = 1.0f)

        PolygonPassiveReceiver().handleFix(justInside)
        PolygonPassiveReceiver().handleFix(justOutside)

        coVerify(exactly = 1) { mockController.activate(VENUE_ID, justInside, any(), any(), any()) }
        coVerify(exactly = 0) { mockController.activate(VENUE_ID, justOutside, any(), any(), any()) }
    }

    @Test
    fun receiver_expectTheGenerationIsReadBeforeAnythingIsDispatched() = runTest {
        // The host's log dispatcher runs mid-handler, so an identify can land before dispatch. The
        // stub bumps the generation there, so only a read on entry passes 7.
        var generation = 7L
        every { mockStore.userStateGeneration() } answers { generation }
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } answers {
            generation = 8L
            emptySet()
        }

        PolygonPassiveReceiver().handleFix(fixNorthOfVenue(10.0, accuracyMeters = 5.0f))

        coVerify { mockController.activate(VENUE_ID, any(), 7L, null, any()) }
    }

    @Test
    fun receiver_givenAnActivePolygonIsDecisivelyOutside_expectASyntheticCoarseExit() = runTest {
        // Only a GMS coarse EXIT clears coarse-inside, and a polygon activated from an ENTER GMS
        // never issued has none coming, so without this it stays active for good.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        val fix = fixNorthOfVenue(300.0, accuracyMeters = 20.0f)

        PolygonPassiveReceiver().handleFix(fix)

        coVerify(exactly = 1) {
            mockController.onCoarseExit(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = 7L,
                expectedRegionRevision = null,
                expectedTeardownGeneration = any()
            )
        }
        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun receiver_expectTheDepartureThresholdIsPinnedToTheRadius() = runTest {
        // Ten metres either side of the boundary: 150 - 20 = 130 clears the 120 m circle, and
        // 130 - 20 = 110 does not.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        val clears = fixNorthOfVenue(150.0, accuracyMeters = 20.0f)
        val doesNotClear = fixNorthOfVenue(130.0, accuracyMeters = 20.0f)

        PolygonPassiveReceiver().handleFix(clears)
        PolygonPassiveReceiver().handleFix(doesNotClear)

        coVerify(exactly = 1) {
            mockController.onCoarseExit(VENUE_ID, clears, any(), any(), any())
        }
        coVerify(exactly = 0) {
            mockController.onCoarseExit(VENUE_ID, doesNotClear, any(), any(), any())
        }
    }

    @Test
    fun receiver_givenTheFixReportsNoAccuracy_expectNoClear() = runTest {
        // accuracy is 0 when unset, which would make the margin vanish and clear on a fix whose
        // error is unknown.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        val noAccuracy = Location("test").apply {
            latitude = VENUE_LAT + 300.0 / METRES_PER_DEGREE_LATITUDE
            longitude = VENUE_LNG
            elapsedRealtimeNanos = 5_000_000_000L
        }

        PolygonPassiveReceiver().handleFix(noAccuracy)

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun receiver_givenTheOutsideFixIsTooCoarseToDecide_expectNoClear() = runTest {
        // 300 m out with 250 m of error establishes nothing, so it must not tear a live session
        // down.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)

        PolygonPassiveReceiver().handleFix(fixNorthOfVenue(300.0, accuracyMeters = 250.0f))

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun receiver_givenTheOutsidePolygonWasNeverActive_expectNothingToClear() = runTest {
        // No coarse state to clear; the exit path would churn the dedupe memo and record a
        // departure from somewhere the user never arrived.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns emptySet()

        PolygonPassiveReceiver().handleFix(fixNorthOfVenue(300.0, accuracyMeters = 20.0f))

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun monitor_givenNothingWasEverRegistered_expectStopDoesNotMintTheRequest() = runTest {
        // FLAG_NO_CREATE keeps a stop from creating the very PendingIntent it is removing.
        val mockClient: FusedLocationProviderClient = mockk(relaxed = true)
        val mockLogger: GeofenceLogger = mockk(relaxed = true)

        GmsPolygonPassiveMonitor(applicationMock, mockClient, mockLogger).stop()

        verify(exactly = 0) { mockClient.removeLocationUpdates(any<PendingIntent>()) }
        verify(exactly = 0) { mockLogger.logPolygonPassiveStopped() }
    }

    @Test
    fun monitor_givenStopped_expectItReportsItselfDisarmed() = runTest {
        // Removing the GMS request leaves the intent alive, so without the cancel a fix already
        // dispatched arrives after teardown and finds a registration that still looks live.
        val mockClient: FusedLocationProviderClient = mockk(relaxed = true)
        val monitor = GmsPolygonPassiveMonitor(applicationMock, mockClient, mockk(relaxed = true))

        monitor.start()
        val armedWhileRegistered = monitor.isArmed()
        monitor.stop()

        armedWhileRegistered shouldBeEqualTo true
        monitor.isArmed() shouldBeEqualTo false
    }

    @Test
    fun teardown_expectEveryPathThatDisablesGeofencingStopsTheListener() = runTest {
        // Otherwise a sign-out or the kill switch leaves the GMS request live across process death,
        // and every other app's fix wakes this process.
        val paths = listOf<Pair<String, (PolygonGeofenceServiceController) -> Unit>>(
            "invalidateOsRegistrationState" to { it.invalidateOsRegistrationState() },
            "stopAll" to { it.stopAll() },
            "clearUserScopedState" to { it.clearUserScopedState() },
            "clearUserSessionRetainingOsRegistrations" to {
                it.clearUserSessionRetainingOsRegistrations()
            },
            "completeUserReset" to { it.completeUserReset(7L, osRegistrationsCleared = true) }
        )

        val observed = paths.associate { (name, teardown) ->
            val passive = RecordingPassiveMonitor()
            teardown(controller(passive))
            name to passive.calls.toList()
        }

        observed shouldBeEqualTo paths.associate { (name, _) -> name to listOf("stop") }
    }

    @Test
    fun receiver_expectTheTeardownTokenIsTheOneReadOnEntryNotAtDispatch() = runTest {
        // isArmed() answers for one instant, so only the token read on entry catches a teardown
        // before activation. The stub moves it part way through the handler.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        var teardowns = 3L
        every { mockController.teardownGeneration() } answers { teardowns }
        every { mockStore.getActivePolygonIds() } answers {
            teardowns = 4L
            emptySet()
        }
        val fix = fixAt(VENUE_LAT, VENUE_LNG)

        PolygonPassiveReceiver().handleFix(fix)

        coVerify {
            mockController.activate(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = any(),
                expectedRegionRevision = null,
                expectedTeardownGeneration = 3L
            )
        }
        coVerify(exactly = 0) {
            mockController.activate(any(), any(), any(), any(), expectedTeardownGeneration = 4L)
        }
    }

    @Test
    fun receiver_expectTheTeardownTokenReachesTheDeparturePathToo() = runTest {
        // onCoarseExit keeps a polygon still in the entered set active, so without the token this
        // path could undo the same teardown.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        every { mockController.teardownGeneration() } returns 3L
        val fix = fixNorthOfVenue(300.0, accuracyMeters = 20.0f)

        PolygonPassiveReceiver().handleFix(fix)

        coVerify {
            mockController.onCoarseExit(
                polygonId = VENUE_ID,
                triggeringLocation = fix,
                expectedUserStateGeneration = any(),
                expectedRegionRevision = null,
                expectedTeardownGeneration = 3L
            )
        }
    }

    @Test
    fun teardown_givenADeliveryLandsDuringTheStop_expectItIsNotLeftActive() = runTest {
        // A delivery between the token bump and the cancel passes both checks, so the listener
        // must stop before the wipe. It runs inside stop(); a stateful store shows what it left.
        val active = mutableSetOf(VENUE_ID)
        every { mockStore.getActivePolygonIds() } answers { active.toSet() }
        every { mockStore.activatePolygon(any()) } answers { active += firstArg<String>() }
        every { mockStore.clearActivePolygonIds() } answers { active.clear() }
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegion(VENUE_ID) } returns venueRegion()
        every { mockStore.activeUserSessionId() } returns "user-1"
        var subject: PolygonGeofenceServiceController? = null
        var delivered = false
        val passive = object : PolygonPassiveMonitor {
            override fun start() = Unit
            override fun isArmed(): Boolean = true
            override fun stop() {
                if (delivered) return
                delivered = true
                // Dispatched before this cancel; reads the token on entry, as the receiver does.
                val controller = subject ?: return
                controller.activate(
                    polygonId = VENUE_ID,
                    expectedUserStateGeneration = 7L,
                    expectedRegionRevision = null,
                    expectedTeardownGeneration = controller.teardownGeneration()
                )
            }
        }
        val controller = controller(passive).also { subject = it }

        controller.stopAll()

        delivered shouldBeEqualTo true
        active shouldBeEqualTo emptySet()
    }

    @Test
    fun teardown_expectTheListenerIsStoppedBeforeAnyStateIsWiped() = runTest {
        // Three paths delegate the wipe to the store, so the order itself is asserted.
        val paths = listOf<Pair<String, (PolygonGeofenceServiceController) -> Unit>>(
            "invalidateOsRegistrationState" to { it.invalidateOsRegistrationState() },
            "stopAll" to { it.stopAll() },
            "clearUserScopedState" to { it.clearUserScopedState() },
            "clearUserSessionRetainingOsRegistrations" to {
                it.clearUserSessionRetainingOsRegistrations()
            },
            "completeUserReset" to { it.completeUserReset(7L, osRegistrationsCleared = true) }
        )

        val observed = paths.associate { (name, teardown) ->
            val order = mutableListOf<String>()
            val wipe = { if (order.none { it == "wipe" }) order += "wipe" }
            every { mockStore.clearActivePolygonIds() } answers { wipe() }
            every { mockStore.clearUserScopedState() } answers { wipe() }
            every { mockStore.clearUserSessionRetainingOsRegistrations() } answers { wipe() }
            every { mockStore.completeUserReset(any(), any()) } answers { wipe() }
            every { mockStore.saveRegisteredIds(any()) } answers { wipe() }
            val passive = object : PolygonPassiveMonitor {
                override fun start() = Unit
                override fun isArmed(): Boolean = true
                override fun stop() {
                    if (order.none { it == "stop" }) order += "stop"
                }
            }
            teardown(controller(passive))
            name to order.toList()
        }

        observed shouldBeEqualTo paths.associate { (name, _) -> name to listOf("stop", "wipe") }
    }

    private fun controller(passive: PolygonPassiveMonitor) = PolygonGeofenceServiceController(
        context = applicationMock,
        store = mockStore,
        engine = mockk(relaxed = true),
        approachMonitor = mockk(relaxed = true),
        manager = mockk(relaxed = true),
        secureUserStore = mockSecureUserStore,
        freshFixSource = NeverAnswersFreshFix,
        recheckScheduler = NoopRecheckScheduler,
        passiveMonitor = passive,
        bootSessionProvider = { "boot" },
        logger = mockk(relaxed = true)
    )

    private fun venueRegion() = GeofenceRegion(
        id = VENUE_ID,
        latitude = VENUE_LAT,
        longitude = VENUE_LNG,
        radius = 120.0f,
        polygonVertices = listOf(
            PolygonCoordinate(VENUE_LAT - 0.0005, VENUE_LNG - 0.0005),
            PolygonCoordinate(VENUE_LAT - 0.0005, VENUE_LNG + 0.0005),
            PolygonCoordinate(VENUE_LAT + 0.0005, VENUE_LNG + 0.0005),
            PolygonCoordinate(VENUE_LAT + 0.0005, VENUE_LNG - 0.0005)
        )
    )

    /** The fixed metres-per-degree is ~0.4% off here, so tests stay 10 m clear of the boundary. */
    private fun fixNorthOfVenue(metres: Double, accuracyMeters: Float) = Location("test").apply {
        latitude = VENUE_LAT + metres / METRES_PER_DEGREE_LATITUDE
        longitude = VENUE_LNG
        accuracy = accuracyMeters
        elapsedRealtimeNanos = 5_000_000_000L
    }

    private fun fixAt(
        latitude: Double,
        longitude: Double,
        elapsedRealtimeNanos: Long = 5_000_000_000L
    ) = Location("test").apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = 18.0f
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
    }

    private companion object {
        const val METRES_PER_DEGREE_LATITUDE = 111_320.0
        const val VENUE_ID = "venue"
        const val VENUE_LAT = 24.0
        const val VENUE_LNG = 67.0
    }
}
