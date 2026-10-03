package io.customer.geofence.polygon

import android.location.Location
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coarse polygon ENTER against the real [GeofenceRegionStoreImpl]. Calls
 * [PolygonGeofenceServiceController.activate] directly, so receiver routing and the
 * region-revision check are not covered.
 */
@RunWith(RobolectricTestRunner::class)
class PolygonCoarseEnterRealStoreTest : RobolectricTest() {

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var controller: PolygonGeofenceServiceController

    private val engine: PolygonLocationEngine = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)

    // A small rectangle (about 270 m by 230 m) inside a 1 km wake circle.
    private val fence16 = GeofenceRegion(
        id = "16",
        latitude = -8.914570,
        longitude = 22.429073,
        radius = 1000f,
        polygonVertices = listOf(
            PolygonCoordinate(-8.915770, 22.428036),
            PolygonCoordinate(-8.915770, 22.430110),
            PolygonCoordinate(-8.913370, 22.430110),
            PolygonCoordinate(-8.913370, 22.428036)
        ),
        baseRadiusMeters = 104.0
    )

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        )
        store.clearAll()
        every { secureUserStore.getUserId() } returns "user-42"
        store.beginUserSession("user-42")
        store.saveCachedRegions(listOf(fence16))
        store.saveRegisteredIds(setOf("16"))
        store.saveRoutableRegisteredIds(setOf("16"))
        controller = PolygonGeofenceServiceController(
            context = applicationMock,
            store = store,
            engine = engine,
            approachMonitor = approachMonitor,
            manager = manager,
            secureUserStore = secureUserStore,
            freshFixSource = NeverAnswersFreshFix,
            recheckScheduler = NoopRecheckScheduler,
            passiveMonitor = NoopPassiveMonitor,
            logger = mockk(relaxed = true)
        )
    }

    /** Inside the ring. */
    private fun arrivalFix() = Location("gps").apply {
        latitude = -8.914750
        longitude = 22.429790
        accuracy = 39.4f
        elapsedRealtimeNanos = 1_000_000_000L
    }

    @Test
    fun activate_givenDeliveredCoarseEnterForARoutablePolygon_expectTheStoreRecordsIt() = runTest {
        controller.activate(
            polygonId = "16",
            triggeringLocation = arrivalFix(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getCoarseInsidePolygonIds() shouldContain "16"
        store.getActivePolygonIds() shouldContain "16"
    }
}
