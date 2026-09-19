package io.customer.geofence

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.NeverAnswersFreshFix
import io.customer.geofence.polygon.NoopPassiveMonitor
import io.customer.geofence.polygon.NoopRecheckScheduler
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Session ownership is decided by two paths that can run at once, so state assertions alone do not
 * cover it. These tests run one path against the real store while the other lands inside it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeofenceSessionInterleavingTest : RobolectricTest() {

    private lateinit var store: GeofenceRegionStoreImpl

    private val repository: GeofenceRepository = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val permissionChecker: GeofencePermissionChecker = mockk {
        every { hasRequiredLocationPermissions() } returns true
        every { isBackgroundDeliveryAvailable() } returns true
    }
    private val clock: Clock = mockk { every { elapsedRealtime() } returns 10_000L }

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        )
        store.clearAll()
        coEvery { repository.refresh(any(), any()) } returns Result.success(Unit)
    }

    private fun servicesWith(scope: TestScope) = GeofenceServicesImpl(
        repository = repository,
        secureUserStore = secureUserStore,
        regionStore = store,
        cooldownFilter = mockk(relaxed = true),
        polygonController = mockk(relaxed = true),
        scope = scope,
        logger = mockk(relaxed = true),
        permissionChecker = permissionChecker,
        clock = clock
    )

    @Test
    fun onAppLaunch_givenAnIdentifyLandsBetweenTheReadAndTheStore_expectTheNewerSessionKept() =
        runTest(StandardTestDispatcher()) {
            // Launch reads user-a, an identify for user-b opens and arms its session, then launch
            // reaches the store. Reopening user-a here would clear routing with no pass queued to
            // re-arm it, so the next business callback would be dropped as unarmed.
            every { secureUserStore.getUserId() } answers {
                store.beginUserSession(USER_B)
                store.saveRoutableRegisteredIdsIfCurrent(setOf(FENCE), store.userStateGeneration())
                USER_A
            }
            val generationBefore = store.userStateGeneration()

            servicesWith(this).onAppLaunch(latitude = 1.0, longitude = 2.0)
            advanceUntilIdle()

            store.activeUserSessionId() shouldBeEqualTo USER_B
            store.getRoutableRegisteredIds() shouldContainSame setOf(FENCE)
            // The identify's own open is the only one that moved the generation.
            store.userStateGeneration() shouldBeEqualTo generationBefore + 1L
        }

    @Test
    fun onAppLaunch_givenNoSession_expectLaunchOpensOne() = runTest(StandardTestDispatcher()) {
        every { secureUserStore.getUserId() } returns USER_A

        servicesWith(this).onAppLaunch(latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        store.activeUserSessionId() shouldBeEqualTo USER_A
    }

    @Test
    fun polygonSessionOpen_givenAnIdentifyRacesTheLaunchRead_expectTheIdentifiedUserOwnsIt() {
        // The launch, boot and callback paths all reach the store through this one controller
        // entry point. The read it hands down runs under the session lock, so an identify on
        // another thread cannot land between the read and the open and be reopened as the older
        // user. Threads, not a re-entrant stub: the whole point is that the other path blocks.
        val controller = PolygonGeofenceServiceController(
            context = applicationMock,
            store = store,
            engine = mockk(relaxed = true),
            approachMonitor = mockk(relaxed = true),
            manager = mockk(relaxed = true),
            secureUserStore = secureUserStore,
            freshFixSource = NeverAnswersFreshFix,
            recheckScheduler = NoopRecheckScheduler,
            passiveMonitor = NoopPassiveMonitor,
            logger = mockk(relaxed = true)
        )
        store.beginUserSession(USER_A)
        val readStarted = CountDownLatch(1)
        val identifyDone = CountDownLatch(1)
        every { secureUserStore.getUserId() } answers {
            readStarted.countDown()
            identifyDone.await(2, TimeUnit.SECONDS)
            USER_A
        }

        val launch = Thread { controller.beginUserSessionForCurrentUser() }
        val identify = Thread {
            readStarted.await(2, TimeUnit.SECONDS)
            store.beginUserSession(USER_B)
            store.saveRoutableRegisteredIdsIfCurrent(setOf(FENCE), store.userStateGeneration())
            identifyDone.countDown()
        }
        launch.start()
        identify.start()
        launch.join(5_000L)
        identify.join(5_000L)

        store.activeUserSessionId() shouldBeEqualTo USER_B
        store.getRoutableRegisteredIds() shouldContainSame setOf(FENCE)
    }

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val FENCE = "g-1"
    }
}
