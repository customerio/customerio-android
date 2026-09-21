package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceBusinessTransitionProcessor
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionEmitter
import io.customer.geofence.PolygonFreshFixSkip
import io.customer.geofence.store.GeofenceRegionStoreImpl
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * Asking for a precise fix when the delivered one decided nothing.
 *
 * The case is the ordinary indoor one, which is also the one the pipeline is worst at. A ~110 m
 * venue has an arrival ceiling of 50 m, and the fixes we get to judge it with are GMS's own
 * geofence triggering fixes, which on a parked device measure 100 m and upwards. So the delivered
 * fix decides nothing, and nothing better follows it: the approach sampling session has never
 * contributed a distinct fix to an evaluation in four field captures. Waiting is not a strategy,
 * so the fix has to be asked for while the process is still awake.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonFreshFixTest : RobolectricTest() {

    private val emitter: GeofenceTransitionEmitter = mockk(relaxed = true)
    private val secureUserStore: SecureUserStore = mockk(relaxed = true)
    private val approachMonitor: PolygonApproachMonitor = mockk(relaxed = true)
    private val manager: GeofenceManager = mockk(relaxed = true)
    private val mockLogger: GeofenceLogger = mockk(relaxed = true)
    private val clock: Clock = mockk(relaxed = true)

    private lateinit var store: GeofenceRegionStoreImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        ).also { it.clearAll() }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))

        every { secureUserStore.getUserId() } returns USER_ID
        every { clock.currentTimeSeconds() } returns 100L
        every { clock.currentTimeMillis() } returns 100_000L
        coEvery {
            emitter.emitWithRetainedAttempt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns GeofenceTransitionEmitter.Result.PERSISTED
        coEvery { emitter.recoverPendingTransitions() } returns true
        coEvery { manager.replaceMovementTrigger(any()) } returns Result.success(Unit)

        store.beginUserSession(USER_ID)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))
    }

    @Test
    fun activate_givenTheDeliveredFixCannotDecide_expectAPreciseFixIsAskedForAndDecides() = runTest {
        val freshFix = AnswersOnceFreshFix(preciseFixInsideTheVenue())
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 1
        store.getEnteredIds() shouldContain VENUE_ID
        // Confirmed, not guessed. That is the whole value of asking: the delivered fix could not
        // decide at all, and this one is clear of the ring by six times its own uncertainty.
        verify(exactly = 1) {
            mockLogger.logPolygonDecided(VENUE_ID, "ENTER", any(), any(), any(), corroborated = any())
        }
    }

    @Test
    fun activate_givenNoFixOnTheCallbackAtAll_expectItAsksRatherThanEvaluatingNothing() = runTest {
        // GMS sometimes delivers a transition with no location attached. Before this the callback
        // evaluated nothing whatsoever, which is the one case where a request is the entire pass
        // rather than a second opinion.
        val freshFix = AnswersOnceFreshFix(preciseFixInsideTheVenue())
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = null,
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 1
        store.getEnteredIds() shouldContain VENUE_ID
    }

    @Test
    fun activate_givenASecondUndecidedFenceInTheSameBatch_expectOneRequestAnsweringBoth() = runTest {
        // One GMS batch can name several polygons and the receiver dispatches each separately, so
        // without reuse a batch of undecided fences opens the GPS once per fence. The fix already
        // obtained answers the rest of the batch while it is still young.
        val freshFix = AnswersOnceFreshFix(preciseFixInsideTheVenue())
        val controller = controller(freshFix)
        store.saveCachedRegions(listOf(venueRegion(), neighbourRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        controller.activate(
            polygonId = NEIGHBOUR_ID,
            triggeringLocation = coarseFixInsideTheVenue(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L
            ),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 1
        // The reused fix is a real evaluation, not a skipped one: it decided the venue both times
        // it was consulted, and the second callback recorded no rate-limit refusal.
        verify(exactly = 0) { mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.WITHIN_COOLDOWN) }
    }

    @Test
    fun activate_givenThePreciseFixDecides_expectTheMovementTriggerRecentredOnIt() = runTest {
        // The trigger is re-centred on whatever fix was accepted, and only the precise one can do
        // it: the movement policy refuses any fix coarser than 50 m, so the 122 m fix this
        // callback delivered cannot re-anchor the next re-fetch on its own.
        val controller = controller(AnswersOnceFreshFix(preciseFixInsideTheVenue()))

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        coVerify(exactly = 1) { manager.replaceMovementTrigger(any()) }
    }

    @Test
    fun activate_givenOnlyACoarseFixAndNoPreciseOne_expectTheMovementTriggerLeftAlone() = runTest {
        // The control for the test above. Without it, a passing assertion there proves only that
        // something re-centred the trigger, not that the precise fix did.
        val controller = controller(NeverAnswersFreshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        coVerify(exactly = 0) { manager.replaceMovementTrigger(any()) }
    }

    @Test
    fun activate_givenTheFixDecidesButOnlyMarginally_expectAPreciseFixIsAskedForToCorroborate() = runTest {
        // cor=true appears in no capture: nothing in the background ever supplied the second
        // measurement a held arrival waits for, so every marginal arrival expired. The fix that
        // held it is also the only fix the batch has, so the request is the one thing that can
        // answer it.
        val freshFix = AnswersOnceFreshFix(preciseFixInsideTheVenue())
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = marginalFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 1
        // The precise fix clears the ring on its own, so the hold ends as an arrival rather than
        // by expiring. This is the field case the branch exists for, end to end.
        store.getEnteredIds() shouldContain VENUE_ID
    }

    @Test
    fun activate_givenTheDeliveredFixDecides_expectNoPreciseFixIsAskedFor() = runTest {
        // The cheap path must stay cheap. A fix that already decides must not spend the sensor, or
        // every ordinary drive-by crossing pays for a GPS request it did not need.
        val freshFix = AnswersOnceFreshFix(preciseFixInsideTheVenue())
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = preciseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 0
        store.getEnteredIds() shouldContain VENUE_ID
    }

    @Test
    fun activate_givenNoPreciseFixArrives_expectTheDeliveredVerdictStandsAndIsRecorded() = runTest {
        // A GPS fix indoors is exactly the case most likely not to arrive, so this is the common
        // path rather than an edge. The delivered verdict is unchanged and the capture says why,
        // which is what separates "we asked and got nothing" from "we never asked".
        val controller = controller(NeverAnswersFreshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        store.getEnteredIds() shouldNotContain VENUE_ID
        verify(exactly = 1) { mockLogger.logPolygonFreshFixRequested(listOf(VENUE_ID)) }
        verify(exactly = 1) { mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.NONE_ARRIVED) }
    }

    @Test
    fun activate_givenTheBatchWindowHasPassed_expectTheObtainedFixIsNotReused() = runTest {
        // The reuse window is sized to one GMS batch, measured at 0.07 s to 0.36 s. Three seconds
        // later is a different moment, and a fix from a different moment must not answer for this
        // one: the engine's own fix-age ceiling is 120 s, so nothing downstream would catch it.
        //
        // What happens instead is the rate limit, which is still armed, so the callback decides on
        // what it was given. That refusal is the observable difference between a window of seconds
        // and a window of half a minute.
        val freshFix = AnswersOnceFreshFix(
            coarseFixInsideTheVenue(elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos())
        )
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            ),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 1
        verify(exactly = 1) { mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.WITHIN_COOLDOWN) }
    }

    @Test
    fun activate_givenASecondUndecidedFixInsideTheCooldown_expectOnlyOneRequest() = runTest {
        // The sampling session delivers every 15 s and a device sitting indoors is undecided on
        // every one of them, so without the cooldown this holds the GPS open for the whole
        // session. The refusal is recorded rather than silent, because a capture otherwise cannot
        // tell a rate limit from a fix that never came.
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            ),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 1
        verify(exactly = 1) { mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.WITHIN_COOLDOWN) }
    }

    @Test
    fun activate_givenTheUserSessionWasClearedInsideTheCooldown_expectTheNextUserMayAskAgain() = runTest {
        // The rate limit is not user-scoped by its key, but it has to be by its lifetime. Signing
        // out leads to a sync and a registration with INITIAL_TRIGGER_ENTER, so the next user's
        // first callback lands within seconds of the last one; carrying the cooldown across would
        // refuse that user their own fix because someone else asked for one.
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        controller.clearUserScopedState()
        // What identify does next: a new session, a sync, and a registration.
        store.beginUserSession("user-2")
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))
        every { secureUserStore.getUserId() } returns "user-2"
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            ),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 2
    }

    @Test
    fun activate_givenAnyUserScopeResetInsideTheCooldown_expectTheNextCallbackMayAskAgain() = runTest {
        // The test above covers one reset entry point out of six, and review found that removing
        // the reset from two of the others changed nothing any test could see. The rate limit is
        // keyed globally and scoped by lifetime, so every entry point that ends a user's scope has
        // to lift it; enumerating them is the only way to keep that true as they are added.
        val entryPoints: List<Pair<String, (PolygonGeofenceServiceController) -> Unit>> = listOf(
            "clearUserScopedState" to { it.clearUserScopedState() },
            "clearUserSessionRetainingOsRegistrations" to {
                it.clearUserSessionRetainingOsRegistrations()
            },
            "invalidateOsRegistrationState" to { it.invalidateOsRegistrationState() },
            "stopAll" to { it.stopAll() },
            "completeUserReset" to {
                it.completeUserReset(
                    expectedUserStateGeneration = store.userStateGeneration(),
                    osRegistrationsCleared = true
                )
            },
            "beginUserSession" to { it.beginUserSession("user-2") }
        )
        val unlifted = mutableListOf<String>()

        entryPoints.forEach { (name, reset) ->
            resetStoreToASingleRegisteredVenue()
            val freshFix = CountingNeverAnswersFreshFix()
            val controller = controller(freshFix)

            controller.activate(
                polygonId = VENUE_ID,
                triggeringLocation = coarseFixInsideTheVenue(),
                expectedUserStateGeneration = store.userStateGeneration(),
                expectedRegionRevision = null
            )
            reset(controller)
            // What the SDK does next whichever reset ran: a session, a sync, a registration.
            store.beginUserSession("user-2")
            store.saveCachedRegions(listOf(venueRegion()))
            store.saveRegisteredIds(setOf(VENUE_ID))
            store.saveRoutableRegisteredIds(setOf(VENUE_ID))
            every { secureUserStore.getUserId() } returns "user-2"
            controller.activate(
                polygonId = VENUE_ID,
                triggeringLocation = coarseFixInsideTheVenue(
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                ),
                expectedUserStateGeneration = store.userStateGeneration(),
                expectedRegionRevision = null
            )

            // One request is the first callback's; the second is what the reset has to allow.
            if (freshFix.requests < 2) unlifted += "$name (${freshFix.requests} requests)"
        }

        // Collected rather than asserted per entry point, so a failure names every reset that
        // carried the cooldown across rather than the first one.
        unlifted.shouldBeEqualTo(emptyList())
    }

    @Test
    fun activate_givenTheCooldownHasElapsed_expectItAsksAgain() = runTest {
        // The control for the test above: a rate limit that never lifts is an off switch, and the
        // session is two minutes long, so it has to allow more than one attempt.
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = coarseFixInsideTheVenue(
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            ),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 2
    }

    private class CountingNeverAnswersFreshFix : PolygonFreshFixSource {
        var requests: Int = 0
            private set

        override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
            requests++
            return null
        }
    }

    /** The setup()'s store state, re-established so each reset entry point starts from it. */
    private fun resetStoreToASingleRegisteredVenue() {
        store.clearAll()
        every { secureUserStore.getUserId() } returns USER_ID
        store.beginUserSession(USER_ID)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))
    }

    private fun controller(freshFixSource: PolygonFreshFixSource) = PolygonGeofenceServiceController(
        context = applicationMock,
        store = store,
        engine = PolygonLocationEngine(
            store = store,
            transitionProcessor = GeofenceBusinessTransitionProcessor(
                store,
                secureUserStore,
                emitter,
                mockLogger
            ),
            clock = clock,
            logger = mockLogger
        ),
        approachMonitor = approachMonitor,
        manager = manager,
        secureUserStore = secureUserStore,
        freshFixSource = freshFixSource,
        recheckScheduler = NoopRecheckScheduler,
        passiveMonitor = NoopPassiveMonitor,
        logger = mockLogger
    )

    /**
     * The fix the parked phone actually produces: 122 m of accuracy, dead centre of a venue ~110 m
     * across. It reads inside, and it decides nothing, because 122 m of uncertainty against a
     * venue this size carries no information about containment — the accuracy circle covers the
     * venue and most of the street around it. This is the ordinary indoor case, not an edge.
     */
    /**
     * Inside, but only 5.6 m past the southern edge, so at 20 m of uncertainty the fix decides
     * ENTER and the evaluator still asks for a second measurement. Both arrivals lost in the field
     * were this shape: one read 6.7 m inside its ring at 10.9 m accuracy.
     */
    private fun marginalFixInsideTheVenue() = Location("test").apply {
        latitude = 37.77455
        longitude = -122.4194
        accuracy = 20f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        time = 100_000L
    }

    private fun coarseFixInsideTheVenue(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
    ) = Location("test").apply {
        latitude = 37.7750
        longitude = -122.4194
        accuracy = 122.4f
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = 100_000L
    }

    /** The same spot, asked for properly: 8 m of uncertainty instead of 122. */
    private fun preciseFixInsideTheVenue() = Location("test").apply {
        latitude = 37.7750
        longitude = -122.4194
        accuracy = 8f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        time = 100_000L
    }

    // Roughly 110 m x 105 m, so a fix at its centre sits ~52 m from the nearest edge and the
    // venue's own scale is ~27 m. Its arrival ceiling is therefore the floor, 50 m.
    private fun venueRegion() = GeofenceRegion(
        id = VENUE_ID,
        latitude = 37.7750,
        longitude = -122.4194,
        radius = 101f,
        polygonVertices = listOf(
            PolygonCoordinate(37.7745, -122.4200),
            PolygonCoordinate(37.7745, -122.4188),
            PolygonCoordinate(37.7755, -122.4188),
            PolygonCoordinate(37.7755, -122.4200)
        )
    )

    /** A second polygon at the same place, so one batch can report both as undecided. */
    private fun neighbourRegion() = venueRegion().copy(id = NEIGHBOUR_ID)

    private companion object {
        const val USER_ID = "user-1"
        const val VENUE_ID = "venue"
        const val NEIGHBOUR_ID = "neighbour"
    }
}
