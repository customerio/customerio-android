package io.customer.geofence.polygon

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
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
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonApproachIntegrationTest : RobolectricTest() {
    private lateinit var store: GeofenceRegionStoreImpl

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
    }

    @Test
    fun passiveApproachFixes_givenPolygonEnter_expectDurableFineGrainedTransition() = runTest {
        val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
        val secureUserStore: SecureUserStore = mockk {
            every { getUserId() } returns USER_ID
        }
        val logger: GeofenceLogger = mockk(relaxed = true)
        val clock: Clock = mockk {
            every { currentTimeSeconds() } returns 100L
        }
        coEvery { emitter.recoverPendingTransitions() } returns true
        coEvery {
            emitter.emitWithRetainedAttempt(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns GeofenceTransitionEmitter.Result.PERSISTED
        store.saveCachedRegions(listOf(polygonRegion()))
        store.beginUserSession(USER_ID)
        store.saveRegisteredIds(setOf(POLYGON_ID))
        store.saveRoutableRegisteredIds(setOf(POLYGON_ID))

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val engine = PolygonLocationEngine(
            client = mockk<FusedLocationProviderClient>(relaxed = true),
            store = store,
            transitionProcessor = GeofenceBusinessTransitionProcessor(
                store = store,
                secureUserStore = secureUserStore,
                transitionEmitter = emitter,
                logger = logger
            ),
            clock = clock,
            dispatchersProvider = object : DispatchersProvider {
                override val background = dispatcher
                override val main = dispatcher
                override val default = dispatcher
            },
            logger = logger
        )
        val controller = PolygonGeofenceServiceController(
            context = mockk<Context>(relaxed = true),
            store = store,
            engine = engine,
            approachMonitor = mockk(relaxed = true),
            secureUserStore = secureUserStore,
            logger = logger
        )
        val now = SystemClock.elapsedRealtimeNanos()
        val locations = listOf(
            insideLocation(now - 4_000_000_000L),
            insideLocation(now - 2_000_000_000L),
            insideLocation(now)
        )

        controller.processApproachLocations(
            locations = locations,
            expectedUserStateGeneration = store.userStateGeneration()
        )

        store.getEnteredIds() shouldContainSame setOf(POLYGON_ID)
        store.getCoarseInsidePolygonIds() shouldContainSame emptySet()
        coVerify(exactly = 1) {
            emitter.emitWithRetainedAttempt(
                POLYGON_ID,
                Event.GeofenceTransition.ENTER,
                USER_ID,
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

    private fun insideLocation(elapsedRealtimeNanos: Long) = Location("test").apply {
        latitude = 37.775
        longitude = -122.4194
        accuracy = 5f
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
    }

    private fun polygonRegion() = GeofenceRegion(
        id = POLYGON_ID,
        latitude = 37.775,
        longitude = -122.4194,
        radius = 1_200f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    )

    private companion object {
        const val USER_ID = "user-1"
        const val POLYGON_ID = "campus"
    }
}
