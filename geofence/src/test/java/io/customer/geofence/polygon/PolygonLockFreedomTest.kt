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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * Every polygon record must reach the host's log dispatcher with neither `controllerLock` nor
 * `stateLock` held.
 *
 * That dispatcher is customer-supplied code. A slow lambda inside either lock stalls the path that
 * holds it: `stateLock` gates every polygon evaluation, `controllerLock` gates every coarse
 * callback, activation and teardown. So the whole returned-record design in
 * [PolygonRouteProcessor.process] and the five [PolygonLocationEngine] teardown methods exists to
 * keep logging outside them.
 *
 * Until this test that was a structural claim: true by inspection, enforced by nothing, and undone
 * by anyone adding a `logger.` call inside either block. The check itself is cheap and needs no
 * concurrency, because `Thread.holdsLock` answers it on the calling thread. The tests below simply
 * drive the real paths with a [Logger] that refuses to be called while a lock is held.
 *
 * Adding a path here is worth more than adding a per-record assertion: this fails for any record on
 * any path, including ones added later.
 *
 * ## What the controls do and do not cover
 *
 * [aRecordEmittedUnderALock_expectTheHarnessCatchesIt] proves the harness can see a violation, but
 * it probes with a lock this test owns, so on its own it would still pass if
 * [PolygonLocationEngine.holdsStateLock] or [PolygonGeofenceServiceController.holdsControllerLock]
 * were wired to the wrong object. `holdsControllerLock` is therefore asserted directly from inside
 * the lock in [theControllerLockPredicate_expectItReportsTrueFromInsideTheLock], using a seam
 * production already has.
 *
 * `holdsStateLock` has no such seam and was validated by mutation instead: a peer review on
 * 2026-09-18 confirmed that a `logger` call placed inside `synchronized(stateLock)` kills
 * [heldArrival_expectThePendingRecordIsEmittedOutsideBothLocks], and one inside
 * `synchronized(controllerLock)` in `deactivateLocked` kills
 * [coarseCallbacksAndTeardowns_expectEveryRecordIsEmittedOutsideBothLocks]. Both predicates fire.
 *
 * A sweep is not a substitute for naming a path. Reporting `onCoarseExit`'s discards inside its
 * own lock kills only
 * [coarseExitWithNoFixWhileAnArrivalIsHeld_expectTheDiscardIsEmittedOutsideBothLocks], because
 * the sweep unregisters the polygon before it gets there.
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
     * interrogates. Absent, the assertion is skipped rather than silently passing on null.
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
        // Not load-bearing for the assertions, which count logger calls rather than parse them.
        // It is here so a failure names the offending record: without the `ev=` tail the report is
        // a list of prose sentences, and this test's entire output is that list.
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
            logger = logger
        )
        lockHeld = { controller.holdsControllerLock() || engine.holdsStateLock() }
    }

    @After
    fun restoreDiagnostics() = GeofenceDiagnostics.setEnabledForTesting(null)

    @Test
    fun heldArrival_expectThePendingRecordIsEmittedOutsideBothLocks() = runTest {
        // The record this PR exists to produce, on the path that produces it: a marginal fix decides
        // ENTER and holds. It is emitted from inside PolygonRouteProcessor.process, which the engine
        // calls while stateLock is held, so it can only be lock-free by being returned.
        controller.activate(
            polygonId = VENUE_ID,
            expectedUserStateGeneration = store.userStateGeneration()
        )
        engine.processResponsiveLocation(marginalFix())

        assertNoViolations()
    }

    @Test
    fun sessionTornDownWhileAnArrivalIsHeld_expectTheDiscardIsEmittedOutsideBothLocks() = runTest {
        // SESSION_ENDED comes out of engine.stop(), and every caller of it is inside
        // controllerLock. This is the path that forced the engine to return its discards.
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
        // The nested case, which the sweep below cannot reach because it unregisters the polygon
        // first. onCoarseExit holds controllerLock across its final block and tears the session
        // down from inside it, so a teardown that discards a hold reports it while the outer lock
        // is still held unless the ids are carried out. Both preconditions are needed: a fix would
        // resolve the hold before the teardown, and an unregistered polygon returns early.
        val generation = store.userStateGeneration()
        controller.activate(polygonId = VENUE_ID, expectedUserStateGeneration = generation)
        engine.processResponsiveLocation(marginalFix())

        controller.onCoarseExit(VENUE_ID, triggeringLocation = null, expectedUserStateGeneration = generation)

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

        assertNoViolations()
    }

    @Test
    fun aRecordEmittedUnderALock_expectTheHarnessCatchesIt() {
        // The positive control for the mechanism, and the reason to trust the three tests above.
        // Without it they pass just as well when `record` never collects, when the predicate is
        // wired inside out, or when Thread.holdsLock is misused.
        //
        // Deliberately probed with a lock this test owns rather than a production one. Reaching
        // controllerLock or stateLock from here would mean exposing a test-only way to run code
        // while holding a lock that gates every geofence callback, which is a worse thing to own
        // than the gap it closes. What this pins is the harness: predicate plus collection.
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
        // Closes the gap the probe-lock control leaves: it validates the harness, not the
        // production predicate. publishRegistrationIfCurrent already invokes a caller-supplied
        // lambda inside controllerLock, so this needs no new production surface.
        var heldInside: Boolean? = null

        val published = controller.publishRegistrationIfCurrent(
            expectedUserStateGeneration = store.userStateGeneration(),
            userId = USER_ID
        ) { heldInside = controller.holdsControllerLock() }

        published shouldBeEqualTo true
        heldInside shouldBeEqualTo true
    }

    private fun assertNoViolations() {
        // Non-vacuity first. An empty violation list means nothing if the path logged nothing at
        // all, which is how this test would rot if a record were removed or a gate moved above it.
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
