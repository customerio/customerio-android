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
 * The passive listener: fixes another app already paid for.
 *
 * It guarantees nothing by design — on a device where nothing else asks for location, none arrive —
 * so what can be pinned is that it is registered for exactly as long as a polygon is, that it never
 * asks a sensor to turn on, and that a delivered fix goes through the ordinary coarse-ENTER path
 * rather than a second evaluation route.
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
        // Without a floor, a maps app in navigation delivers a fix a second and every one reaches
        // the evaluator.
        request.minUpdateIntervalMillis shouldBeEqualTo GmsPolygonPassiveMonitor.MINIMUM_INTERVAL_MS
    }

    @Test
    fun reconcile_expectTheListenerFollowsRegistrationExactly() {
        val passive = RecordingPassiveMonitor()
        every { mockStore.getActivePolygonIds() } returns emptySet()
        val controller = controller(passive)

        controller.reconcileRegisteredPolygons(setOf("venue"))
        controller.reconcileRegisteredPolygons(emptySet())

        // Order, not counts: starting and then stopping leaves it off, and a count assertion would
        // pass for either ordering.
        passive.calls shouldBeEqualTo listOf("start", "stop")
    }

    @Test
    fun receiver_givenTheRegistrationWasStopped_expectNoActivation() = runTest {
        // Raised by Shahroz on #897. A fix the OS dispatched before teardown completed still
        // arrives afterwards, and teardown leaves the routable ids and the user generation in
        // place on purpose, so neither check activate() already makes can refuse it. The
        // registration is the one thing that did change.
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
        // reconcile is the only other caller of stop(), and it runs from the registration paths
        // alone, so a sign-out or the kill switch leaves this request live with GMS across process
        // death and no later registration ever arrives to remove it. Without self-healing here,
        // every other app's fix wakes our process forever to read the store and return.
        every { mockStore.getRoutableRegisteredIds() } returns emptySet()
        every { mockStore.getCachedRegions() } returns emptyList()

        PolygonPassiveReceiver().handleFix(fixAt(VENUE_LAT, VENUE_LNG))

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
        recordingPassiveMonitor.calls shouldBeEqualTo listOf("stop")
    }

    @Test
    fun receiver_givenAPolygonIsStillRegistered_expectTheListenerIsLeftAlone() = runTest {
        // The control for the test above: retiring on a delivery that had work to do would switch
        // the listener off on the first fix it ever used.
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
        // Replaces a fixture that put the fix at the exact centre and its negative case ~111 km
        // away, which asserted nothing about the radius: a mutant narrowing the circle to half its
        // size passed all of these tests. The venue circle is 120 m, so 110 m admits, 130 m does
        // not.
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
        // A constant stub let the read move after the admission work and nothing noticed. The
        // window is real: logPolygonPassiveReceived forwards to the host's log dispatcher, which
        // is customer code, so an identify can land between the read and the dispatch. Moving the
        // store read later would attribute this fix to whoever is current when it lands.
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
        // activate() records the polygon coarse-inside and only a GMS coarse EXIT clears it. A
        // polygon this listener activated from an ENTER GMS never issued has no such EXIT coming,
        // so without this it stays active for the life of the install.
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
                expectedRegionRevision = null
            )
        }
        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun receiver_expectTheDepartureThresholdIsPinnedToTheRadius() = runTest {
        // The decisively-outside test alone left the threshold unpinned: 300 m out with 20 m error
        // clears a 120 m circle and would equally clear a 240 m one, so a mutant that doubled the
        // radius survived it. Ten metres either side of the boundary pins the subtraction:
        // 150 - 20 = 130 clears 120, and 130 - 20 = 110 does not.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)
        val clears = fixNorthOfVenue(150.0, accuracyMeters = 20.0f)
        val doesNotClear = fixNorthOfVenue(130.0, accuracyMeters = 20.0f)

        PolygonPassiveReceiver().handleFix(clears)
        PolygonPassiveReceiver().handleFix(doesNotClear)

        coVerify(exactly = 1) { mockController.onCoarseExit(VENUE_ID, clears, any(), any()) }
        coVerify(exactly = 0) { mockController.onCoarseExit(VENUE_ID, doesNotClear, any(), any()) }
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

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any()) }
    }

    @Test
    fun receiver_givenTheOutsideFixIsTooCoarseToDecide_expectNoClear() = runTest {
        // The phantom-EXIT guard. 300 m out with 250 m of error establishes nothing, and tearing a
        // live session down on a fix like that is the failure this component already paid for three
        // times.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns setOf(VENUE_ID)

        PolygonPassiveReceiver().handleFix(fixNorthOfVenue(300.0, accuracyMeters = 250.0f))

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any()) }
    }

    @Test
    fun receiver_givenTheOutsidePolygonWasNeverActive_expectNothingToClear() = runTest {
        // The control. A registered polygon never activated has no coarse state to clear, and
        // calling the exit path would churn the dedupe memo and record a departure from somewhere
        // the user never arrived.
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())
        every { mockStore.getActivePolygonIds() } returns emptySet()

        PolygonPassiveReceiver().handleFix(fixNorthOfVenue(300.0, accuracyMeters = 20.0f))

        coVerify(exactly = 0) { mockController.onCoarseExit(any(), any(), any(), any()) }
    }

    @Test
    fun monitor_givenNothingWasEverRegistered_expectStopDoesNotMintTheRequest() = runTest {
        // FLAG_NO_CREATE is what keeps a stop from creating the very PendingIntent it is removing.
        // Without it a stop on a build that never registered would report polygon.passive.stopped,
        // and the capture could not tell a real teardown from a no-op.
        val mockClient: FusedLocationProviderClient = mockk(relaxed = true)
        val mockLogger: GeofenceLogger = mockk(relaxed = true)

        GmsPolygonPassiveMonitor(applicationMock, mockClient, mockLogger).stop()

        verify(exactly = 0) { mockClient.removeLocationUpdates(any<PendingIntent>()) }
        verify(exactly = 0) { mockLogger.logPolygonPassiveStopped() }
    }

    @Test
    fun monitor_givenStopped_expectItReportsItselfDisarmed() = runTest {
        // What makes the receiver's check answerable. Removing the GMS request stops new
        // deliveries but leaves the intent alive, so without the cancel a fix already dispatched
        // arrives after teardown and finds a registration that still looks live.
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
        // The listener reached one stop call site, inside reconcileRegisteredPolygons, so a
        // sign-out or the kill switch left the GMS request live across process death and every
        // other app's fix woke this process to read the store and return.
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

    /**
     * A fix [metres] due north of the venue centre. Derived from a fixed metres-per-degree, ~0.4%
     * off at this latitude, so it is sub-metre accurate over these distances but not exact: the
     * tests stay ten metres clear of the boundary for that reason.
     */
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
