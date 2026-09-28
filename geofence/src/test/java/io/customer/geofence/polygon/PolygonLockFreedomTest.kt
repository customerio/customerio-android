package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceDiagnostics
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionEmitter
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Clock
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotContain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * Every polygon record must reach the host's log dispatcher with neither `controllerLock` nor
 * `stateLock` held: the dispatcher is customer code, and a slow lambda under either lock stalls
 * every polygon evaluation or coarse callback. The tests drive real paths with a [Logger] that
 * collects any call made while `Thread.holdsLock` reports either lock held.
 *
 * [aRecordEmittedUnderALock_expectTheHarnessCatchesIt] validates the harness with a probe lock, and
 * [theControllerLockPredicate_expectItReportsTrueFromInsideTheLock] validates
 * [PolygonGeofenceServiceController.holdsControllerLock]. [PolygonLocationEngine.holdsStateLock] has
 * no equivalent seam.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonLockFreedomTest : RobolectricTest() {

    private val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)

    private lateinit var store: GeofenceRegionStoreImpl
    private lateinit var engine: PolygonLocationEngine
    private lateinit var controller: PolygonGeofenceServiceController

    /**
     * Set after construction, because the logger has to exist before the engine and controller it
     * queries. Unset, `record` fails rather than passing vacuously.
     */
    private var lockHeld: (() -> Boolean)? = null

    private val violations = mutableListOf<String>()
    private var emissions = 0
    private lateinit var lockAssertingLogger: LockAssertingLogger

    private inner class LockAssertingLogger : Logger {
        override var logLevel: CioLogLevel = CioLogLevel.DEBUG

        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit

        override fun info(message: String, tag: String?) = record(message)
        override fun debug(message: String, tag: String?) = record(message)
        override fun error(message: String, tag: String?, throwable: Throwable?) = record(message)

        private fun record(message: String) {
            // requireNotNull, not error(): inside a Logger implementation, error() resolves to
            // this interface's own error method, so the elvis branch types as Any and the result
            // stops being callable.
            val isLockHeld = requireNotNull(lockHeld) {
                "lockHeld was never wired; this test would pass vacuously"
            }
            emissions += 1
            // Collected rather than thrown: a throw here surfaces as whatever the production code
            // does with a logging failure, which could be swallowed by a catch and turn a real
            // violation into a green test.
            if (isLockHeld()) violations += message
        }
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        // Only so a failure names the offending record via its `ev=` tail; the assertions count
        // logger calls rather than parse them.
        GeofenceDiagnostics.setEnabledForTesting(true)

        every { secureUserStore.getUserId() } returns USER_ID
        every { clock.currentTimeSeconds() } returns 100L
        every { clock.currentTimeMillis() } returns 100_000L
        coEvery {
            emitter.emitWithRetainedAttempt(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns GeofenceTransitionEmitter.Result.PERSISTED
        coEvery { emitter.recoverPendingTransitions() } returns true
        coEvery { manager.replaceMovementTrigger(any()) } returns Result.success(Unit)

        store.beginUserSession(USER_ID)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))

        lockAssertingLogger = LockAssertingLogger()
        val logger = GeofenceLogger(lockAssertingLogger)
        engine = PolygonLocationEngine(
            store = store,
            transitionProcessor = GeofenceBusinessTransitionProcessor(
                store,
                secureUserStore,
                emitter,
                logger
            ),
            clock = clock,
            logger = logger
        )
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
            logger = logger
        )
        lockHeld = { controller.holdsControllerLock() || engine.holdsStateLock() }
    }

    @After
    fun restoreDiagnostics() = GeofenceDiagnostics.setEnabledForTesting(null)

    @Test
    fun heldArrival_expectThePendingRecordIsEmittedOutsideBothLocks() = runTest {
        // The pending record is decided inside PolygonRouteProcessor.process, which the engine
        // calls under stateLock, so it can only be lock-free by being returned.
        controller.activate(
            polygonId = VENUE_ID,
            expectedUserStateGeneration = store.userStateGeneration()
        )
        engine.processResponsiveLocation(marginalFix())

        assertNoViolations()
    }

    @Test
    fun sessionTornDownWhileAnArrivalIsHeld_expectTheDiscardIsEmittedOutsideBothLocks() = runTest {
        // SESSION_ENDED comes out of engine.stop(), which every caller invokes inside
        // controllerLock.
        controller.activate(
            polygonId = VENUE_ID,
            expectedUserStateGeneration = store.userStateGeneration()
        )
        engine.processResponsiveLocation(marginalFix())

        controller.stopAll()

        assertNoViolations()
    }

    @Test
    fun coarseExitWithNoFixWhileAnArrivalIsHeld_expectTheDiscardIsEmittedOutsideBothLocks() = runTest {
        // The sweep below cannot reach this because it unregisters the polygon first. onCoarseExit
        // tears the session down inside controllerLock, so the discarded ids must be carried out.
        // No fix, because a fix would resolve the hold before the teardown.
        val generation = store.userStateGeneration()
        controller.activate(polygonId = VENUE_ID, expectedUserStateGeneration = generation)
        engine.processResponsiveLocation(marginalFix())

        // The teardown branch runs only for a polygon not committed INSIDE; a confirmed arrival
        // would make this test cover nothing.
        store.getEnteredIds() shouldNotContain VENUE_ID

        controller.onCoarseExit(VENUE_ID, triggeringLocation = null, expectedUserStateGeneration = generation)

        // The session is gone, so the teardown branch did run.
        store.getActivePolygonIds() shouldNotContain VENUE_ID
        assertNoViolations()
    }

    @Test
    fun coarseCallbacksAndTeardowns_expectEveryRecordIsEmittedOutsideBothLocks() = runTest {
        // A sweep rather than one assertion per record: these are the controllerLock paths that
        // log, and the point is that none of them may log while holding it.
        val generation = store.userStateGeneration()
        controller.activate(polygonId = VENUE_ID, expectedUserStateGeneration = generation)
        controller.onCoarseExit(VENUE_ID, triggeringLocation = null, expectedUserStateGeneration = generation)
        controller.resetEvidence(VENUE_ID)
        controller.reconcileRegisteredPolygons(emptySet())
        controller.recover()
        controller.onCoarseExit(VENUE_ID, insideFix(), generation)
        controller.onMovementTriggerExit(insideFix(), generation)
        controller.processApproachLocations(listOf(insideFix()), generation, Long.MAX_VALUE)
        // Drives the Undecided record too, which no other path here reaches.
        engine.processResponsiveLocation(tooCoarseFix())
        controller.beginUserSessionForCurrentUser()
        controller.invalidatePersistedCoarseState()
        controller.invalidateOsRegistrationState()
        controller.clearUserSessionRetainingOsRegistrations()
        controller.clearUserScopedState()
        controller.completeUserReset(store.userStateGeneration(), osRegistrationsCleared = true)
        controller.beginUserSession(USER_ID)

        assertNoViolations()
    }

    @Test
    fun aRecordEmittedUnderALock_expectTheHarnessCatchesIt() {
        // Positive control for the harness (predicate plus collection). A test-owned lock, because
        // running code under a production lock would need a test-only hook into a lock that gates
        // every geofence callback.
        val probe = Any()
        lockHeld = { Thread.holdsLock(probe) }

        synchronized(probe) { lockAssertingLogger.debug("emitted under a lock", null) }

        check(violations.isNotEmpty()) {
            "the harness cannot see a record emitted under a lock, so the other tests here prove " +
                "nothing"
        }
    }

    @Test
    fun theControllerLockPredicate_expectItReportsTrueFromInsideTheLock() {
        // The probe-lock control validates the harness, not the production predicate.
        // publishRegistrationIfCurrent already runs a caller lambda inside controllerLock.
        var heldInside: Boolean? = null

        val published = controller.publishRegistrationIfCurrent(
            expectedUserStateGeneration = store.userStateGeneration(),
            userId = USER_ID
        ) { heldInside = controller.holdsControllerLock() }

        published shouldBeEqualTo true
        heldInside shouldBeEqualTo true
    }

    private fun assertNoViolations() {
        // An empty violation list means nothing if the path logged nothing at all.
        check(emissions > 0) { "no record was emitted on this path, so it asserts nothing" }
        check(violations.isEmpty()) {
            "records emitted while controllerLock or stateLock was held:\n" +
                violations.joinToString("\n") { "  $it" }
        }
    }

    private fun marginalFix() = Location("test").apply {
        latitude = 37.77459
        longitude = -122.4194
        accuracy = 18f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        time = 100_000L
    }

    private fun insideFix(ageNanos: Long = 2_000_000_000L) = Location("test").apply {
        latitude = 37.7750
        longitude = -122.4194
        accuracy = 5f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageNanos
        time = 100_000L
    }

    // Coarser than the decisive accuracy ceiling, so it is evaluated and decides nothing.
    private fun tooCoarseFix() = Location("test").apply {
        latitude = 37.7750
        longitude = -122.4194
        accuracy = 80f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 500_000_000L
        time = 100_000L
    }

    private fun venueRegion() = GeofenceRegion(
        id = VENUE_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 400f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    )

    private companion object {
        const val USER_ID = "user-1"
        const val VENUE_ID = "venue"
    }
}
