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
    fun activate_givenThePreciseFixIsTheHeldFixAgain_expectTheArrivalCommits() = runTest {
        // The 2026-09-24 field case: the triggering fix was 0.2 s old, so GMS answered the request
        // with that same fix, stamp included, and the hold was lost.
        val held = marginalFixInsideTheVenue()
        val freshFix = AnswersOnceFreshFix(Location(held))
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, held, store.userStateGeneration(), null)

        freshFix.requests shouldBeEqualTo 1
        store.getEnteredIds() shouldContain VENUE_ID
    }

    @Test
    fun activate_givenTheHeldFixArrivesByAnotherPathWhileTheRequestIsOut_expectTheAnswerStillDecides() = runTest {
        // The same fix reaching the fence through approach sampling while the request is still out
        // is not the answer. Letting it settle the hold would commit the arrival before the newer
        // answer below, which places the device outside, could break it.
        val held = marginalFixInsideTheVenue()
        lateinit var controller: PolygonGeofenceServiceController
        val freshFix = object : PolygonFreshFixSource {
            override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
                controller.processApproachLocations(
                    listOf(Location(held)),
                    store.userStateGeneration(),
                    sessionDeadlineElapsedRealtimeMs = Long.MAX_VALUE
                )
                return fixJustSouthOfTheVenue()
            }
        }
        controller = controller(freshFix)

        controller.activate(VENUE_ID, held, store.userStateGeneration(), null)

        store.getEnteredIds() shouldNotContain VENUE_ID
    }

    @Test
    fun activate_givenAReusedAnswerIsTheFixAHoldWasOpenedOn_expectItDoesNotSettleIt() = runTest {
        // Only the answer to a request made from the held fix settles its hold. A reused answer from
        // an earlier callback is a copy of that hold's fix here, not an answer to it. The answer is
        // 1 s old so the reuse below sits well inside its 2 s window.
        val freshFix = AnswersOnceFreshFix(
            marginalFixInsideTheVenue().apply {
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L
            }
        )
        val controller = controller(freshFix)
        store.saveCachedRegions(listOf(venueRegion(), neighbourRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))

        // The answer is marginal, so the venue holds on it.
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos() - 3_000_000_000L),
            store.userStateGeneration(),
            null
        )
        // Older than that answer, so the venue skips it; the neighbour is undecided and reuses it.
        controller.activate(
            NEIGHBOUR_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos() - 2_500_000_000L),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 1
        store.getEnteredIds() shouldNotContain VENUE_ID
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
        //
        // The second callback comes from 150 m away on purpose. A stationary repeat is now
        // suppressed by the futile-escalation check, which would confound this: the assertion
        // would fail for a reason that has nothing to do with the cooldown. Moving the device
        // leaves the cooldown as the only thing under test, which is what this test is for.
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
            triggeringLocation = fixMetresNorthOfTheVenue(150.0),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 2
    }

    @Test
    fun activate_givenAPreciseFixAlreadyFailedFromHere_expectItDoesNotAskAgain() = runTest {
        // Measured 2026-09-20: a device parked inside one polygon's wake circle asked 190 times in
        // a day, median 6 minutes apart, and every answer was undecided. The 30 s cooldown is far
        // shorter than the wake cadence, so nothing stopped it.
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()) }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        // Past the cooldown, so a suppression here is the position check rather than the rate
        // limit. Without this the test would pass on the old code.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 1
        verify(exactly = 1) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenTheDeviceMovedFurtherThanTheFixError_expectItAsksAgain() = runTest {
        // The discriminator is movement, not time. 150 m exceeds the 122 m error of both fixes, so
        // the device demonstrably moved; the new spot is still undecided, so the escalation is
        // still worth making.
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()) }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            fixMetresNorthOfTheVenue(150.0),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
    }

    @Test
    fun activate_givenTheRetryWindowElapsed_expectItAsksAgainEvenParked() = runTest {
        // Accuracy at a fixed position is bimodal on this hardware, either about 1 m or exactly
        // 100 m, so a parked device does occasionally get a fix that would decide. A suppression
        // that never lifted would be an off switch.
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()) }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
    }

    @Test
    fun activate_givenAPassAbortedAfterTheFix_expectAnEarlierSuppressionSurvives() = runTest {
        // Found by Bugbot on #899. recordEscalationOutcomes read "absent from stillUndecided" as
        // "decided" and dropped the memo, but an aborted pass reports nothing undecided without
        // having decided anything. Here the device parks, escalates futilely, moves 150 m so the
        // next wake is allowed to ask, and that request aborts because its fix is too old for the
        // session. Returning to the parked position must still be suppressed: nothing has shown
        // that position is answerable, and the abort said nothing about it either way.
        var requestCount = 0
        val freshFix = CountingCoarseFreshFix {
            requestCount++
            if (requestCount == 2) {
                coarseFixInsideTheVenue(elapsedRealtimeNanos = 1L)
            } else {
                coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos())
            }
        }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            fixMetresNorthOfTheVenue(150.0),
            store.userStateGeneration(),
            null
        )
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
    }

    @Test
    fun activate_givenTheRequestedFixIsOneTheFenceAlreadySaw_expectAnEarlierSuppressionSurvives() = runTest {
        // Raised by Shahroz on #899, and not the aborted-pass case above: this pass is accepted, so
        // the acceptedFix guard cannot catch it. The route processor skips a fence whose fix is not
        // strictly newer than the one it last saw for it, leaving no record for that fence at all.
        // Reading that silence as "decided" dropped a memo the pass had said nothing about, and the
        // parked loop resumed on the next wake.
        //
        // Park and escalate futilely so a memo exists, move 150 m so the next wake is allowed to
        // ask, and let that request answer with the stamp its own triggering fix already recorded
        // for the fence. Returning to the parked spot must still be suppressed.
        //
        // The single lambda serves both passes because the triggering fixes differ in age: the
        // parked fix is 2 s old so the answer is newer and gets judged, while the 150 m fix carries
        // the current stamp so the answer ties with it and the fence is skipped.
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()) }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            fixMetresNorthOfTheVenue(150.0),
            store.userStateGeneration(),
            null
        )
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
        verify(exactly = 1) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenAResetWhileSuspendedAndNoFix_expectNoMemoFromTheOldRequest() = runTest {
        // Raised by Shahroz on #899. The memo is written after awaitFreshFix suspended, and a
        // teardown in that window clears futileEscalations. This is the path the aborted-pass guard
        // cannot cover: when no fix arrives there is no evaluation to abort, so the continuation
        // wrote the cleared memo straight back from the triggering fix. The next session's first
        // callback at that position then skipped its own request for the whole retry window.
        var resetDuringRequest: (() -> Unit)? = null
        val freshFix = object : PolygonFreshFixSource {
            var requests: Int = 0
                private set

            override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
                requests++
                resetDuringRequest?.invoke()
                return null
            }
        }
        val controller = controller(freshFix)
        resetDuringRequest = { controller.invalidatePersistedCoarseState() }

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)

        resetDuringRequest = null
        resetStoreToASingleRegisteredVenue()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
    }

    @Test
    fun activate_givenAReusedFixWasAlsoUndecided_expectItSuppressesThatFencesNextRequest() = runTest {
        // The half of the reuse contract the reset test cannot pin. Refusing to record a reused fix
        // would also keep a teardown from restoring an old memo, so "no stale memo" passes either
        // way; what only the token delivers is that a reused fix still counts as a futile
        // escalation for the fence it was judged against. One request answers the whole batch, so
        // dropping that would leave every fence after the first asking again on its own next wake.
        //
        // The neighbour is registered after the first callback so its memo can only come from the
        // reused fix: had it been active earlier, the first pass would have recorded it directly
        // and this would pass without the token.
        val base = SystemClock.elapsedRealtimeNanos()
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(elapsedRealtimeNanos = base) }
        val controller = controller(freshFix)

        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(elapsedRealtimeNanos = base - 5_000_000_000L),
            store.userStateGeneration(),
            null
        )

        store.saveCachedRegions(listOf(venueRegion(), neighbourRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))

        controller.activate(
            NEIGHBOUR_ID,
            coarseFixInsideTheVenue(elapsedRealtimeNanos = base - 3_000_000_000L),
            store.userStateGeneration(),
            null
        )

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            NEIGHBOUR_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 1
        verify(exactly = 1) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenADecisiveFixThatOnlyAgreed_expectTheSuppressionIsStillCleared() = runTest {
        // evaluatedPolygonIds is derived from every record, not only the deciding ones, and this is
        // what that breadth buys. A fix clear of the ring while the fence is already committed
        // OUTSIDE decides the position without producing a transition: it writes Unchanged. That
        // still proves the position is answerable, so the memo has to go.
        //
        // Park and escalate futilely, move 150 m so the next wake may ask, answer that one with a
        // precise fix that reads decisively outside, then come back. The return must be allowed to
        // ask again, because the position was shown to be answerable in between.
        var requestCount = 0
        val freshFix = CountingCoarseFreshFix {
            requestCount++
            if (requestCount == 2) {
                // A second of the request's own wait, so the answer is strictly newer than the fix
                // that triggered it. Without this the processor skips the fence as not-newer and
                // the pass correctly says nothing, which is the case the test above covers.
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                fixMetresNorthOfTheVenue(150.0, accuracyMeters = 8f)
            } else {
                coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos())
            }
        }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            fixMetresNorthOfTheVenue(150.0),
            store.userStateGeneration(),
            null
        )
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 3
    }

    @Test
    fun activate_givenAResetWhileAReusedFixIsEvaluated_expectNoMemoFromTheOldSession() = runTest {
        // Raised by Shahroz on #899. A reused fix carried no request token, so the memo written
        // after its evaluation could not be checked against a teardown: the continuation wrote the
        // previous session's suppression straight back, and the next session's first callback at
        // that position skipped its own precise request for the whole retry window.
        //
        // The interleaving is built explicitly because it does not fall out of an ordinary batch. A
        // reused fix is normally skipped by every fence, since the triggering fix of the pass that
        // reuses it is newer and the processor only judges a strictly newer stamp. The exception is
        // a fence that became active during this pass, whose stamp is unset: a callback carrying a
        // fix older than the last requested one then leaves the reused fix newer for that fence
        // alone, so it is judged and recorded.
        val base = SystemClock.elapsedRealtimeNanos()
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(elapsedRealtimeNanos = base) }
        val controller = controller(freshFix)

        var neighbourUndecidedCalls = 0
        every {
            mockLogger.logPolygonUndecided(any(), any(), any(), any(), any())
        } answers {
            if (firstArg<String>() == NEIGHBOUR_ID) {
                neighbourUndecidedCalls++
                // The second one is the reused fix's own evaluation, which is the window the memo
                // is written after.
                if (neighbourUndecidedCalls == 2) controller.invalidatePersistedCoarseState()
            }
        }

        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(elapsedRealtimeNanos = base - 5_000_000_000L),
            store.userStateGeneration(),
            null
        )

        // Newly active, so the processor has no stamp for it and the reused fix can still be
        // judged against it.
        store.saveCachedRegions(listOf(venueRegion(), neighbourRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))

        controller.activate(
            NEIGHBOUR_ID,
            coarseFixInsideTheVenue(elapsedRealtimeNanos = base - 3_000_000_000L),
            store.userStateGeneration(),
            null
        )

        // A new session at the same place must get its own first request.
        store.clearAll()
        every { secureUserStore.getUserId() } returns USER_ID
        store.beginUserSession(USER_ID)
        store.saveCachedRegions(listOf(venueRegion(), neighbourRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            NEIGHBOUR_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
        verify(exactly = 0) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenThePreciseFixDecided_expectTheNextEscalationIsStillAllowed() = runTest {
        // Only a futile escalation suppresses the next one. A fix that decided proves this
        // position is answerable, so nothing should be held back afterwards.
        val freshFix = CountingCoarseFreshFix { preciseFixInsideTheVenue() }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
        verify(exactly = 0) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenEveryFenceTheRequestServesIsSuppressed_expectNoRequest() = runTest {
        // The gate used to read the callback's own fence, which is often not one the request would
        // serve at all. A fix that decides the callback's fence clears its memo, so the callback
        // arrives carrying none, while the only fence still needing a fix is already suppressed.
        // The request was funded anyway, once per wake, which is the loop this path exists to stop.
        // Raised by Bugbot twice and reproduced by Shahroz with two active polygons.
        // A second of the request's own wait, so the answer is strictly newer than the fix that
        // triggered it. Without it the processor skips every fence as not-newer, the pass records
        // nothing, and no memo is ever set for the test to exercise.
        val freshFix = CountingCoarseFreshFix {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            preciseFixInsideTheVenue()
        }
        val controller = controller(freshFix)
        store.saveCachedRegions(listOf(venueRegion(), regionJustNorthOfTheVenue(NEIGHBOUR_ID)))
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        // Committed INSIDE, so the venue-centre fix reads outside its ring without clearing it,
        // which is undecided rather than a departure.
        store.recordEntered(NEIGHBOUR_ID)

        controller.activate(NEIGHBOUR_ID, preciseFixInsideTheVenue(), store.userStateGeneration(), null)
        // Past the cooldown, so a refusal below is the position check and not the rate limit.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        // A callback for the venue, whose own verdict is decisive and carries no memo. The only
        // fence needing a fix is still the neighbour, and it is suppressed.
        controller.activate(VENUE_ID, preciseFixInsideTheVenue(), store.userStateGeneration(), null)

        freshFix.requests shouldBeEqualTo 1
        verify(exactly = 1) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenARequestTimedOut_expectTheUndecidedFenceIsStillMemoised() = runTest {
        // The complement of the held-arrival case below, and unpinned until now: a request that
        // answers with nothing still has to memoise the fences it was asked for, or a device whose
        // precise fix never arrives goes on asking on every wake, which is the same waste by
        // another route. Deleting the timeout recording altogether passed the whole suite before
        // this existed.
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 1
        verify(exactly = 1) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenASuppressedFenceThatIsNowHoldingAnArrival_expectItStillAsks() = runTest {
        // The memo records that a precise fix from this position could not decide this fence, which
        // is what made asking again pointless. Once that fence is holding an arrival the premise is
        // gone: a fix at the counted position now settles the hold by committing it, rather than
        // deciding nothing. So the memo no longer covers this case and the request goes ahead.
        //
        // Without the bypass the request is refused, nothing reaches the fence, and the hold is
        // dropped when it goes stale. Field gaps between two fixes for one fence ran 300 s and up
        // against a 60 s window, so the refusal loses the visit rather than delaying it.
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))

        // Coarse at the venue centre: over the fence's 54 m ceiling, so undecided. The request
        // times out, and the timeout memoises the fence at this position.
        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        freshFix.requests shouldBeEqualTo 1

        // Past the cooldown, so a refusal now would be the position check and not the rate limit.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))

        // The same position, inside the ceiling this time, so it reads a marginal arrival and holds
        // it. The fence is the only one the request would serve and it is memoised, which before
        // the bypass was exactly the shape that withheld the fix.
        controller.activate(
            VENUE_ID,
            marginalFixAtTheVenueCentre(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
        verify(exactly = 0) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenAHeldArrivalAndATimedOutRequest_expectTheNextCallbackStillAsks() = runTest {
        // Raised by Shahroz on #901, and a regression the set-wide gate introduced. On the
        // NONE_ARRIVED path nothing was evaluated, so the recording took the whole requested set as
        // still undecided, which handed a futile memo to a fence whose arrival was merely being
        // held. The memo is good for 30 minutes and the hold expires in 60 seconds, so the next
        // callback suppressed the one measurement that could still have saved the arrival.
        //
        // The later fix is coarse on purpose: that is the ordinary case, and it is what puts the
        // held fence back in the request set as undecided rather than resolving its hold.
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)
        store.saveCachedRegions(
            listOf(venueRegion(), regionShiftedNorth(NEIGHBOUR_ID, HELD_CO_TENANT_SHIFT_DEGREES))
        )
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.recordEntered(NEIGHBOUR_ID)

        // The co-tenant has to be activated to be judged at all: the engine evaluates the active
        // fences and activate() admits only the one it is called for. Activated with a decisive fix
        // of its own so this pass asks for nothing, which keeps the cooldown free and leaves no memo
        // behind. Stamped older than the marginal fix below, or the processor would skip it as
        // not-newer when that one arrives.
        controller.activate(
            NEIGHBOUR_ID,
            decisiveFixInsideTheShiftedFence(),
            store.userStateGeneration(),
            null
        )
        // Marginal inside the venue, so this one pass holds the venue's ENTER for a second
        // measurement AND reads the co-tenant undecided. One request serves both, and it times out.
        controller.activate(VENUE_ID, marginalFixInsideTheVenue(), store.userStateGeneration(), null)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()),
            store.userStateGeneration(),
            null
        )

        // The held fence carries no memo, so the set is not wholly suppressed and the hold still
        // gets its request. Memoising it would have refused this one, which is the regression: the
        // co-tenant IS memoised by the same timed-out request, so a whole-set recording leaves
        // nothing in the set unsuppressed.
        freshFix.requests shouldBeEqualTo 2
        verify(exactly = 0) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenOneFenceInTheSetIsStillAnswerable_expectItStillAsks() = runTest {
        // The other half of the contract, and the reason the check is `all` and not `any`. One
        // fence that could still be answered is worth the fix, so a suppressed co-tenant must not
        // withhold it. Suppressing on `any` here is how this turns into a delayed arrival, which
        // is the whole reason the stack exists.
        //
        // The second neighbour is registered only after the first request, so it carries no memo of
        // its own while the first neighbour does.
        // A second of the request's own wait, so the answer is strictly newer than the fix that
        // triggered it. Without it the processor skips every fence as not-newer, the pass records
        // nothing, and no memo is ever set for the test to exercise.
        val freshFix = CountingCoarseFreshFix {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            preciseFixInsideTheVenue()
        }
        val controller = controller(freshFix)
        store.saveCachedRegions(listOf(venueRegion(), regionJustNorthOfTheVenue(NEIGHBOUR_ID)))
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.recordEntered(NEIGHBOUR_ID)

        controller.activate(NEIGHBOUR_ID, preciseFixInsideTheVenue(), store.userStateGeneration(), null)

        store.saveCachedRegions(
            listOf(
                venueRegion(),
                regionJustNorthOfTheVenue(NEIGHBOUR_ID),
                regionJustNorthOfTheVenue(SECOND_NEIGHBOUR_ID)
            )
        )
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID, SECOND_NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID, SECOND_NEIGHBOUR_ID))
        store.recordEntered(SECOND_NEIGHBOUR_ID)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            SECOND_NEIGHBOUR_ID,
            preciseFixInsideTheVenue(),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 2
    }

    @Test
    fun activate_givenTheMoveIsInsideTheOlderFixError_expectItDoesNotAskAgain() = runTest {
        // Pins the tolerance to the LOOSER of the two errors. The first fix was +/-122 m, the
        // second is +/-60 m and reads 100 m away. That displacement is entirely explainable by the
        // first fix's own error, so the device has not been shown to move and asking again would
        // repeat a request that already failed. Taking the tighter error instead would treat
        // measurement noise as movement and re-open the loop.
        //
        // 60 m is above the decisive ceiling on purpose: a tighter fix decides outright and never
        // reaches the escalation, so the tolerance could not be observed at all.
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()) }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(
            VENUE_ID,
            fixMetresNorthOfTheVenue(metres = 100.0, accuracyMeters = 60f),
            store.userStateGeneration(),
            null
        )

        freshFix.requests shouldBeEqualTo 1
        verify(exactly = 1) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    private class CountingCoarseFreshFix(
        private val fix: () -> Location
    ) : PolygonFreshFixSource {
        var requests: Int = 0
            private set

        override suspend fun awaitFreshFix(timeoutMs: Long, priority: PolygonFixPriority): Location? {
            requests++
            return fix()
        }
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

    /**
     * At the venue centre, 52 m from the nearest edge, carrying 53 m of error.
     *
     * Marginal rather than decisive, because the error reaches the ring, and still under the
     * fence's own 54 m ceiling so it is admitted. The admissible-yet-marginal window here is only
     * 52.7 m to 54.1 m wide, so this accuracy is pinned: move it either way and the fix decides
     * outright or is refused, and the test stops exercising a hold.
     *
     * Deliberately at the SAME position as [coarseFixInsideTheVenue]: a futile-escalation memo is
     * keyed on position, so this is the only way one fix can open a hold on a fence that an
     * earlier fix already memoised.
     */
    private fun marginalFixAtTheVenueCentre(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
    ) = Location("test").apply {
        latitude = 37.7750
        longitude = -122.4194
        accuracy = 53f
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = 100_000L
    }

    /**
     * The fix the parked phone actually produces: 122 m of accuracy, dead centre of a venue ~110 m
     * across. It reads inside, and it decides nothing, because 122 m of uncertainty against a
     * venue this size carries no information about containment — the accuracy circle covers the
     * venue and most of the street around it. This is the ordinary indoor case, not an edge.
     */
    private fun coarseFixInsideTheVenue(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
    ) = Location("test").apply {
        latitude = 37.7750
        longitude = -122.4194
        accuracy = 122.4f
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = 100_000L
    }

    /**
     * [metres] due north of the venue centre, carrying the same 122 m error. Far enough that the
     * move is larger than either fix's error, close enough that the verdict is still undecided, so
     * the only thing that changes between this and [coarseFixInsideTheVenue] is the position.
     */
    private fun fixMetresNorthOfTheVenue(
        metres: Double,
        accuracyMeters: Float = 122.4f
    ) = Location("test").apply {
        latitude = 37.7750 + metres / 111_320.0
        longitude = -122.4194
        accuracy = accuracyMeters
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        time = 100_000L
    }

    /** 11 m south of the venue's southern edge at 3 m of uncertainty, so it reads outside. */
    private fun fixJustSouthOfTheVenue() = Location("test").apply {
        latitude = 37.7745 - 11.0 / 111_320.0
        longitude = -122.4194
        accuracy = 3f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
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

    // Roughly 111 m x 106 m, so a fix at its centre sits ~52 m from the nearest edge. Its
    // `2 x area / perimeter` scale is ~54 m, just above the 50 m floor, so this fence is governed by
    // its own depth rather than the floor. The earlier "~27 m" here halved it, which put the fence
    // on the wrong side of that boundary.
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

    /**
     * A polygon shifted ~60 m north, so a fix at the venue's centre sits about 4 m OUTSIDE its
     * southern edge while sitting ~52 m inside the venue's.
     *
     * That is what lets one precise fix reach two different verdicts: decisive for the venue, and,
     * for this one committed INSIDE, outside its ring but not clear of it, which is
     * WITHIN_ACCURACY. Needed because both rings are the same shape, so they share an accuracy
     * ceiling and cannot diverge on fix quality alone.
     */
    private fun regionJustNorthOfTheVenue(id: String) =
        regionShiftedNorth(id, NORTH_SHIFT_DEGREES)

    /**
     * Dead centre of the fence shifted by [HELD_CO_TENANT_SHIFT_DEGREES], precise enough to decide
     * it, and stamped 5 s old so the 2 s old marginal fix that follows is still strictly newer.
     */
    private fun decisiveFixInsideTheShiftedFence() = Location("test").apply {
        latitude = 37.7750 + HELD_CO_TENANT_SHIFT_DEGREES
        longitude = -122.4194
        accuracy = 8f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 5_000_000_000L
        time = 100_000L
    }

    /** [degrees] of latitude north, carrying the venue's shape with it. */
    private fun regionShiftedNorth(id: String, degrees: Double) = venueRegion().copy(
        id = id,
        latitude = 37.7750 + degrees,
        polygonVertices = venueRegion().polygonVertices?.map {
            PolygonCoordinate(it.latitude + degrees, it.longitude)
        }
    )

    private companion object {
        /** ~59.7 m, which puts the venue-centre fix about 4 m south of the shifted fence's edge. */
        const val NORTH_SHIFT_DEGREES = 0.0005359
        const val SECOND_NEIGHBOUR_ID = "neighbour-2"

        /**
         * ~30.6 m, which leaves the marginal fix about 25 m south of the shifted fence's edge:
         * outside it, but not clear of it at 20 m accuracy, so the verdict is undecided rather
         * than a departure.
         */
        const val HELD_CO_TENANT_SHIFT_DEGREES = 0.0002746
        const val USER_ID = "user-1"
        const val VENUE_ID = "venue"
        const val NEIGHBOUR_ID = "neighbour"
    }
}
