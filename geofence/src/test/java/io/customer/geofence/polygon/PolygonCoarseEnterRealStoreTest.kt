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
 * The coarse polygon ENTER path against the real [GeofenceRegionStoreImpl].
 *
 * Written after the 2026-09-16 drive, where the OS had a clean inbound crossing of fence 16's
 * wake circle and the region store recorded nothing at all. The logs that would say whether GMS
 * delivered were lost, so this pins the half we can test without a device: given a delivered
 * callback for a registered, routable polygon, the store must come out changed. A green run here
 * narrows that failure to delivery; a red one would have found it without another drive.
 *
 * Real store rather than a stub of it: every precondition the controller checks reads through
 * [GeofenceRegionStoreImpl], and a relaxed mock answers all of them affirmatively by default,
 * which is exactly the shape that would hide this.
 */
@RunWith(RobolectricTestRunner::class)
class PolygonCoarseEnterRealStoreTest : RobolectricTest() {

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var controller: PolygonGeofenceServiceController

    private val engine: PolygonLocationEngine = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)

    // Fence 16 (Bundu Khan) at its real centre, registered at the 1 km floor as the device had it.
    private val fence16 = GeofenceRegion(
        id = "16",
        latitude = 31.37143,
        longitude = 74.18527,
        radius = 1000f,
        polygonVertices = listOf(
            PolygonCoordinate(31.37023, 74.18407),
            PolygonCoordinate(31.37023, 74.18647),
            PolygonCoordinate(31.37263, 74.18647),
            PolygonCoordinate(31.37263, 74.18407)
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
            secureUserStore = secureUserStore
        )
    }

    /** The arrival fix iOS logged for this crossing: 31.37125/74.18610, acc 39.4. */
    private fun arrivalFix() = Location("gps").apply {
        latitude = 31.37125
        longitude = 74.18610
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
