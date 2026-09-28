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
 * The venue here has an arrival ceiling of ~54 m, so a coarse triggering fix (122 m in these
 * fixtures) decides nothing, and the precise fix is asked for while the process is still awake.
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
        // Confirmed, not held: the precise fix is clear of the ring by six times its uncertainty.
        verify(exactly = 1) {
            mockLogger.logPolygonDecided(VENUE_ID, "ENTER", any(), any(), any(), corroborated = any())
        }
    }

    @Test
    fun activate_givenNoFixOnTheCallbackAtAll_expectItAsksRatherThanEvaluatingNothing() = runTest {
        // GMS sometimes delivers a transition with no location attached, so the request is the
        // entire pass rather than a second opinion.
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
        // Reuse bypasses the rate limit, so the second callback records no cooldown refusal.
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
        // A held marginal arrival waits for a second measurement that background delivery may
        // never supply. The fix that opened the hold is the only one in the batch, so the request
        // is what can answer it.
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
        // by expiring.
        store.getEnteredIds() shouldContain VENUE_ID
    }

    @Test
    fun activate_givenThePreciseFixIsTheHeldFixAgain_expectTheArrivalCommits() = runTest {
        // A very young triggering fix can come back from GMS as the answer, stamp included, and
        // must still settle its hold.
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
        // A requested fix may never arrive (no GPS signal, say). The delivered verdict is unchanged
        // and the capture says why, separating "asked and got nothing" from "never asked".
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
        // The 2 s reuse window covers one GMS batch. Three seconds later the fix must not be
        // reused, since the engine accepts fixes up to 120 s old and nothing downstream would catch
        // it. The still-armed rate limit refuses instead, and that refusal is what this observes.
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
        // The sampling session delivers every 15 s and a coarse fix is undecided each time, so
        // without the cooldown the GPS stays open all session. The refusal is recorded so a rate
        // limit can be told from a fix that never came.
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
        // The rate limit is keyed globally but scoped by lifetime, so every entry point that ends
        // a user's scope must lift it. Enumerated so a new entry point cannot be missed.
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
        // Control for the cooldown tests: the session is two minutes long, so the rate limit must
        // allow more than one attempt. The second callback is 150 m away so the futile-escalation
        // check, which suppresses a stationary repeat, stays out of it.
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
        // A device parked inside a wake circle would ask on every wake and get an undecided answer
        // each time. The 30 s cooldown is far shorter than the wake cadence, so it cannot stop that.
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()) }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        // Past the cooldown, so a suppression here is the position check rather than the rate
        // limit.
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
        // A parked device can still get a fix precise enough to decide, so a suppression that
        // never lifted would be an off switch.
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
        // An aborted pass reports nothing undecided without having decided anything, so it must
        // not drop the memo. The device parks and escalates futilely, moves 150 m so the next wake
        // may ask, and that request aborts because its fix is too old. Returning to the parked
        // position must still be suppressed.
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
        // Unlike the aborted pass above, this pass is accepted. The route processor skips a fence
        // whose fix is not strictly newer than the last it saw, leaving no record, and that silence
        // must not drop the memo. Park and escalate futilely, move 150 m so the next wake may ask,
        // and answer with the stamp that wake's triggering fix already recorded. Returning to the
        // parked spot must still be suppressed.
        //
        // One lambda serves both passes: the parked fix is 2 s old so the answer is newer and
        // judged, while the 150 m fix carries the current stamp so the answer ties and is skipped.
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
        // A teardown while awaitFreshFix is suspended clears the memo. With no fix there is no
        // evaluation to abort, so the no-fix path must not write the memo back from the triggering
        // fix, or the next session's first callback here would skip its own request.
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
        // One request answers the whole batch, so a reused fix still counts as a futile escalation
        // for the fence it was judged against. The neighbour is registered after the first
        // callback, so its memo can only come from the reused fix.
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
        // A fix clear of the ring while the fence is committed OUTSIDE decides without a
        // transition (Unchanged), which still proves the position answerable and clears the memo.
        // Park and escalate futilely, move 150 m so the next wake may ask, answer with a precise
        // fix decisively outside, then come back: the return may ask again.
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
        // A teardown during a reused fix's evaluation must stop its memo being written back, or
        // the next session's first callback at that position skips its own request.
        //
        // Built explicitly: a reused fix is normally skipped by every fence, since the reusing
        // pass's triggering fix is newer and the processor only judges a strictly newer stamp. A
        // fence newly active in this pass has no stamp, so a callback carrying a fix older than
        // the requested one leaves the reused fix newer for that fence alone.
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
        // The gate reads every fence the request would serve, not the callback's own: here the
        // callback's fence decides and carries no memo, while the only fence still needing a fix
        // is suppressed.
        //
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
        // A request that answers with nothing still memoises the fences it was asked for, or a
        // device whose precise fix never arrives asks on every wake.
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
        // A memo says a fix from here could not decide the fence, but once the fence holds an
        // arrival a fix here settles it. Refusing the request would let the hold expire (60 s) and
        // lose the visit.
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
        // it. The fence is the only one the request would serve, and it is memoised.
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
        // On the NONE_ARRIVED path a held arrival must not be memoised as futile: the memo lasts
        // 30 minutes and the hold 60 seconds, so it would suppress the one measurement that could
        // still save the arrival. The later fix is coarse, the ordinary case, which puts the held
        // fence back in the request set as undecided rather than resolving its hold.
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)
        store.saveCachedRegions(
            listOf(venueRegion(), regionShiftedNorth(NEIGHBOUR_ID, HELD_CO_TENANT_SHIFT_DEGREES))
        )
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.recordEntered(NEIGHBOUR_ID)

        // The co-tenant must be active to be judged, and activate() admits only the fence it is
        // called for. Its own decisive fix asks for nothing, so the cooldown stays free and no memo
        // is left. Stamped older than the marginal fix below, or that one would be skipped as
        // not-newer.
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

        // The held fence carries no memo, so the set is not wholly suppressed and the hold gets
        // its request, even though the same timed-out request memoised the co-tenant.
        freshFix.requests shouldBeEqualTo 2
        verify(exactly = 0) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenOneFenceInTheSetIsStillAnswerable_expectItStillAsks() = runTest {
        // Suppression needs `all` fences memoised, not `any`: one fence that could still be
        // answered is worth the fix. The second neighbour is registered only after the first
        // request, so it carries no memo while the first neighbour does.
        //
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
        // Tolerance is the looser of the two errors. The first fix was +/-122 m, the second is
        // +/-60 m and 100 m away, which the first fix's error explains, so the device has not been
        // shown to move. 60 m is above the ceiling so the fix reaches the escalation at all.
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
     * ENTER and the evaluator still asks for a second measurement.
     */
    private fun marginalFixInsideTheVenue() = Location("test").apply {
        latitude = 37.77455
        longitude = -122.4194
        accuracy = 20f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
        time = 100_000L
    }

    /**
     * At the venue centre, 52 m from the nearest edge, with 53 m of error: marginal because the
     * error reaches the ring, admitted because it is under the fence's ~54 m ceiling. That window
     * is only 52.7 m to 54.1 m, so the accuracy is pinned. Same position as
     * [coarseFixInsideTheVenue], since a futile-escalation memo is keyed on position.
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
     * A coarse fix: 122 m of accuracy at the venue centre. It reads inside but
     * decides nothing, being over the fence's ~54 m arrival ceiling.
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
     * [metres] due north of the venue centre, by default with the same 122 m error, so the verdict
     * stays undecided and only the position differs from [coarseFixInsideTheVenue].
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
    // `2 x area / perimeter` scale is ~54 m, just above the 50 m floor, so its own depth sets the
    // arrival ceiling.
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
     * So one precise fix reaches two verdicts: decisive for the venue, and WITHIN_ACCURACY for this
     * one committed INSIDE. Both rings share a shape and so a ceiling, and cannot diverge on fix
     * quality alone.
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
