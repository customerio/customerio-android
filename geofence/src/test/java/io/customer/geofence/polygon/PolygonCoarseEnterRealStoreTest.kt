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
 * wake circle and the region store recorded nothing at all.
 *
 * Scope, deliberately narrow: this proves the controller persists once activation is reached. It
 * calls [PolygonGeofenceServiceController.activate] directly, so it covers neither the receiver's
 * routing and drop paths nor the region-revision check, which passes `null` here. A green run
 * therefore does **not** isolate that drive failure to GMS delivery; routing and revision remain
 * open and have to stay open in the diagnosis until a capture rules them out.
 *
 * Real store rather than a stub of it. Not because a relaxed mock would wave the preconditions
 * through: `getRoutableRegisteredIds()` relaxes to an empty set, which rejects activation instead,
 * so the test would fail for a reason that has nothing to do with the behaviour. The point is that
 * every precondition reads through [GeofenceRegionStoreImpl] and the persistence being asserted is
 * the store's own, so stubbing it would leave nothing real under test.
 */
@RunWith(RobolectricTestRunner::class)
class PolygonCoarseEnterRealStoreTest : RobolectricTest() {

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var controller: PolygonGeofenceServiceController

    private val engine: PolygonLocationEngine = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)

    // The fence from the 2026-09-16 drive, registered at the 1 km floor as the device had it.
    // Coordinates are moved to another latitude band with longitude rescaled by the cosine ratio,
    // so the shape and every distance are preserved and the original position is not recoverable.
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
            logger = mockk(relaxed = true)
        )
    }

    /** The arrival fix iOS logged for this crossing, moved with the ring. Accuracy 39.4 m. */
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
