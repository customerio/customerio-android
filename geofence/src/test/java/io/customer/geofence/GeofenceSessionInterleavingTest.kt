package io.customer.geofence

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.NeverAnswersFreshFix
import io.customer.geofence.polygon.NoopPassiveMonitor
import io.customer.geofence.polygon.NoopRecheckScheduler
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.polygon.PolygonLocationEngine
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
 * cover it. These tests race the paths on real threads against the real store and controller: one
 * path holds its user-id read open while the other tries to open a session.
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

    // Every session change stops the engine; nothing is held here, so nothing is discarded.
    private val polygonEngine: PolygonLocationEngine = mockk(relaxed = true) {
        every { stop() } returns emptySet()
    }

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

    /** Real: launch and identify both open sessions through it, and its locks are what they race. */
    private fun realController() = PolygonGeofenceServiceController(
        context = applicationMock,
        store = store,
        engine = polygonEngine,
        approachMonitor = mockk(relaxed = true),
        manager = mockk(relaxed = true),
        secureUserStore = secureUserStore,
        freshFixSource = NeverAnswersFreshFix,
        recheckScheduler = NoopRecheckScheduler,
        passiveMonitor = NoopPassiveMonitor,
        bootSessionProvider = { "boot" },
        logger = mockk(relaxed = true)
    )

    private fun servicesWith(scope: TestScope) = GeofenceServicesImpl(
        repository = repository,
        secureUserStore = secureUserStore,
        regionStore = store,
        cooldownFilter = mockk(relaxed = true),
        // Not a mock: a relaxed controller opens no session, so launch and identify would do nothing.
        polygonController = realController(),
        scope = scope,
        logger = mockk(relaxed = true),
        permissionChecker = permissionChecker,
        clock = clock
    )

    @Test
    fun onAppLaunch_givenAnIdentifyRacesTheLaunchRead_expectTheIdentifiedSessionKept() =
        runTest(StandardTestDispatcher()) {
            // A relaunch for persisted user-a reads its user while the host identifies user-b. Both
            // open sessions through the one controller, so user-b's open waits for launch's and
            // launch can never reopen user-a over user-b, clearing the routing user-b's pass armed.
            store.beginUserSession(USER_A)
            val generationBefore = store.userStateGeneration()
            val services = servicesWith(this)
            val currentUserId = AtomicReference(USER_A)
            val readStarted = CountDownLatch(1)
            val identifyDone = CountDownLatch(1)
            val race = Race()
            val launch = race.thread { services.onAppLaunch(latitude = 1.0, longitude = 2.0) }
            val identify = race.thread {
                readStarted.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // The host stores the new user before it tells the SDK.
                currentUserId.set(USER_B)
                services.onUserIdentified(latitude = 1.0, longitude = 2.0)
                // Stands in for user-b's sync pass, which arms routing for its own session.
                store.saveRoutableRegisteredIdsIfCurrent(setOf(FENCE), store.userStateGeneration())
                identifyDone.countDown()
            }
            val launchReadPending = AtomicBoolean(true)
            every { secureUserStore.getUserId() } answers {
                val seen = currentUserId.get()
                // Only the first read, launch's, is held open; identify waits for it to start.
                if (launchReadPending.compareAndSet(true, false)) {
                    readStarted.countDown()
                    holdReadOpen(identify, identifyDone)
                }
                seen
            }

            race.run(launch, identify)
            advanceUntilIdle()

            // The race ran: launch's read was held open while identify started.
            readStarted.count shouldBeEqualTo 0L
            store.activeUserSessionId() shouldBeEqualTo USER_B
            store.getRoutableRegisteredIds() shouldContainSame setOf(FENCE)
            // Launch read user-a, the owner already, so its open changed nothing: only identify's
            // open moved the generation.
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
    fun onAppLaunch_givenAnotherUsersPersistedSession_expectTheCurrentUserOwnsIt() =
        runTest(StandardTestDispatcher()) {
            // State persisted for user-a while the host now reports user-b. Launch opens user-b's
            // session, as the first OS callback would, so user-a's routing cannot route user-b's
            // callbacks.
            store.beginUserSession(USER_A)
            store.saveRoutableRegisteredIdsIfCurrent(setOf(FENCE), store.userStateGeneration())
            val generationBefore = store.userStateGeneration()
            every { secureUserStore.getUserId() } returns USER_B

            servicesWith(this).onAppLaunch(latitude = 1.0, longitude = 2.0)
            advanceUntilIdle()

            store.activeUserSessionId() shouldBeEqualTo USER_B
            store.getRoutableRegisteredIds() shouldBeEqualTo emptySet()
            store.userStateGeneration() shouldBeEqualTo generationBefore + 1L
        }

    @Test
    fun polygonSessionOpen_givenAnIdentifyRacesTheLaunchRead_expectTheIdentifiedUserOwnsIt() {
        // The read runs under the store's session lock, so an open straight on the store waits for
        // it and cannot land between read and open. Real threads, not a re-entrant stub: the point
        // is that the other path blocks.
        val controller = realController()
        store.beginUserSession(USER_A)
        val readStarted = CountDownLatch(1)
        val identifyDone = CountDownLatch(1)
        val identifyFinishedDuringRead = AtomicReference<Boolean?>(null)
        val race = Race()
        val launch = race.thread { controller.beginUserSessionForCurrentUser() }
        val identify = race.thread {
            readStarted.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            store.beginUserSession(USER_B)
            store.saveRoutableRegisteredIdsIfCurrent(setOf(FENCE), store.userStateGeneration())
            identifyDone.countDown()
        }
        val launchReadPending = AtomicBoolean(true)
        every { secureUserStore.getUserId() } answers {
            if (launchReadPending.compareAndSet(true, false)) {
                readStarted.countDown()
                identifyFinishedDuringRead.set(holdReadOpen(identify, identifyDone))
            }
            USER_A
        }

        race.run(launch, identify)

        identifyFinishedDuringRead.get() shouldBeEqualTo false
        store.activeUserSessionId() shouldBeEqualTo USER_B
        store.getRoutableRegisteredIds() shouldContainSame setOf(FENCE)
    }

    /**
     * Holds a user-id read open until [other] finishes, counting down [otherDone], or stays blocked on
     * a monitor, which is how a path waiting for a session lock shows. The block must last, so a brief
     * wait on some unrelated lock does not end the read early. Returns whether [other] finished first.
     */
    private fun holdReadOpen(other: Thread, otherDone: CountDownLatch): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RACE_TIMEOUT_SECONDS)
        val settleNanos = TimeUnit.MILLISECONDS.toNanos(BLOCKED_SETTLE_MS)
        var blockedSince: Long? = null
        while (System.nanoTime() < deadline) {
            if (otherDone.await(POLL_MS, TimeUnit.MILLISECONDS)) return true
            val now = System.nanoTime()
            blockedSince = if (other.state == Thread.State.BLOCKED) blockedSince ?: now else null
            if (blockedSince != null && now - blockedSince >= settleNanos) return false
        }
        return otherDone.count == 0L
    }

    /** Threads whose failures fail the test, rather than ending only their own thread. */
    private class Race {
        private val failures = ConcurrentLinkedQueue<Throwable>()

        fun thread(body: () -> Unit): Thread = Thread { body() }.apply {
            setUncaughtExceptionHandler { _, failure -> failures += failure }
        }

        fun run(vararg threads: Thread) {
            threads.forEach(Thread::start)
            threads.forEach { it.join(JOIN_TIMEOUT_MS) }
            failures.peek()?.let { throw AssertionError("A racing path failed", it) }
            threads.forEach { it.isAlive shouldBeEqualTo false }
        }
    }

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val FENCE = "g-1"
        const val RACE_TIMEOUT_SECONDS = 2L
        const val JOIN_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val BLOCKED_SETTLE_MS = 100L
    }
}
