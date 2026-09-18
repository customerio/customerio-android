package io.customer.geofence.polygon

import android.location.Location
import com.google.android.gms.location.Priority
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
                expectedRegionRevision = null
            )
        }
    }

    @Test
    fun receiver_givenTheFixIsOutsideEveryWakeCircle_expectNoActivation() = runTest {
        every { mockStore.getRoutableRegisteredIds() } returns setOf(VENUE_ID)
        every { mockStore.getCachedRegions() } returns listOf(venueRegion())

        PolygonPassiveReceiver().handleFix(fixAt(VENUE_LAT + 1.0, VENUE_LNG))

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any()) }
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

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any()) }
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

        coVerify(exactly = 0) { mockController.activate(any(), any(), any(), any()) }
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
        const val VENUE_ID = "venue"
        const val VENUE_LAT = 24.0
        const val VENUE_LNG = 67.0
    }
}
