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

/** Asking for a precise fix when the delivered one decided nothing. */
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
        // Only the precise fix can re-centre it: the movement policy refuses fixes coarser than 50 m.
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
        val freshFix = AnswersOnceFreshFix(preciseFixInsideTheVenue())
        val controller = controller(freshFix)

        controller.activate(
            polygonId = VENUE_ID,
            triggeringLocation = marginalFixInsideTheVenue(),
            expectedUserStateGeneration = store.userStateGeneration(),
            expectedRegionRevision = null
        )

        freshFix.requests shouldBeEqualTo 1
        store.getEnteredIds() shouldContain VENUE_ID
    }

    @Test
    fun activate_givenThePreciseFixIsTheHeldFixAgain_expectTheArrivalCommits() = runTest {
        // GMS can return a very young triggering fix as the answer, stamp included.
        val held = marginalFixInsideTheVenue()
        val freshFix = AnswersOnceFreshFix(Location(held))
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, held, store.userStateGeneration(), null)

        freshFix.requests shouldBeEqualTo 1
        store.getEnteredIds() shouldContain VENUE_ID
    }

    @Test
    fun activate_givenTheHeldFixArrivesByAnotherPathWhileTheRequestIsOut_expectTheAnswerStillDecides() = runTest {
        // The held fix arriving by approach sampling is not the answer; settling on it would commit
        // the arrival before the newer outside answer could break the hold.
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
        // The reused answer is a copy of the held fix here, not an answer to it. It is 1 s old so the
        // reuse below sits inside the 2 s window.
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
        // Three seconds is past the 2 s reuse window, so the still-armed rate limit refuses instead,
        // and that refusal is what this observes.
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
        // A new entry point that ends a user's scope belongs in this list.
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

        // Collected, so a failure names every reset that carried the cooldown across.
        unlifted.shouldBeEqualTo(emptyList())
    }

    @Test
    fun activate_givenTheCooldownHasElapsed_expectItAsksAgain() = runTest {
        // The second callback is 150 m away, so the futile-escalation check stays out of it.
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
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(SystemClock.elapsedRealtimeNanos()) }
        val controller = controller(freshFix)

        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        // Past the cooldown, so a suppression here is the position check, not the rate limit.
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
        // 150 m exceeds the 122 m error of both fixes, so the device demonstrably moved.
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
        // Park and escalate futilely, move 150 m, and let that request abort on a too-old fix. The
        // aborted pass decided nothing, so returning to the parked spot is still suppressed.
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
        // One lambda serves both passes: the parked fix is 2 s old so the answer is newer and judged,
        // while the 150 m fix carries the current stamp so the answer ties and is skipped silently.
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
        // A teardown while awaitFreshFix is suspended clears the memo, and with no fix there is no
        // evaluation to abort, so the no-fix path must not write it back.
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
        // The neighbour is registered after the first callback, so its memo can only come from the
        // reused fix.
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
        // The second answer is precise and decisively outside a fence committed OUTSIDE: it decides
        // without a transition and still clears the memo.
        var requestCount = 0
        val freshFix = CountingCoarseFreshFix {
            requestCount++
            if (requestCount == 2) {
                // The request's own wait, so the answer is strictly newer than its triggering fix
                // and judged.
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
        // The processor judges only a strictly newer stamp, so a reused fix is judged only by a fence
        // newly active in this pass, which has no stamp yet.
        val base = SystemClock.elapsedRealtimeNanos()
        val freshFix = CountingCoarseFreshFix { coarseFixInsideTheVenue(elapsedRealtimeNanos = base) }
        val controller = controller(freshFix)

        var neighbourUndecidedCalls = 0
        every {
            mockLogger.logPolygonUndecided(any(), any(), any(), any(), any())
        } answers {
            if (firstArg<String>() == NEIGHBOUR_ID) {
                neighbourUndecidedCalls++
                // The second is the reused fix's own evaluation, which the memo write follows.
                if (neighbourUndecidedCalls == 2) controller.invalidatePersistedCoarseState()
            }
        }

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
        // The venue callback decides and carries no memo; the only fence still needing a fix is the
        // suppressed neighbour.
        val freshFix = CountingCoarseFreshFix {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            preciseFixInsideTheVenue()
        }
        val controller = controller(freshFix)
        store.saveCachedRegions(listOf(venueRegion(), regionJustNorthOfTheVenue(NEIGHBOUR_ID)))
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        // Committed INSIDE, so the venue-centre fix reads outside its ring without clearing it.
        store.recordEntered(NEIGHBOUR_ID)

        controller.activate(NEIGHBOUR_ID, preciseFixInsideTheVenue(), store.userStateGeneration(), null)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.activate(VENUE_ID, preciseFixInsideTheVenue(), store.userStateGeneration(), null)

        freshFix.requests shouldBeEqualTo 1
        verify(exactly = 1) {
            mockLogger.logPolygonFreshFixSkipped(PolygonFreshFixSkip.UNCHANGED_POSITION)
        }
    }

    @Test
    fun activate_givenARequestTimedOut_expectTheUndecidedFenceIsStillMemoised() = runTest {
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
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)
        store.saveCachedRegions(listOf(venueRegion()))
        store.saveRegisteredIds(setOf(VENUE_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID))

        // Over the ceiling, so undecided; the request times out and memoises the fence here.
        controller.activate(VENUE_ID, coarseFixInsideTheVenue(), store.userStateGeneration(), null)
        freshFix.requests shouldBeEqualTo 1

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))

        // Same position, inside the ceiling, so it holds a marginal arrival on the memoised fence.
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
        // The later fix is coarse, which puts the held fence back in the request set as undecided
        // rather than resolving its hold.
        val freshFix = CountingNeverAnswersFreshFix()
        val controller = controller(freshFix)
        store.saveCachedRegions(
            listOf(venueRegion(), regionShiftedNorth(NEIGHBOUR_ID, HELD_CO_TENANT_SHIFT_DEGREES))
        )
        store.saveRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.saveRoutableRegisteredIds(setOf(VENUE_ID, NEIGHBOUR_ID))
        store.recordEntered(NEIGHBOUR_ID)

        // Activates the co-tenant on its own decisive fix, stamped older so the marginal fix below is
        // not skipped as not-newer.
        controller.activate(
            NEIGHBOUR_ID,
            decisiveFixInsideTheShiftedFence(),
            store.userStateGeneration(),
            null
        )
        // Holds the venue's ENTER and reads the co-tenant undecided; one request serves both.
        controller.activate(VENUE_ID, marginalFixInsideTheVenue(), store.userStateGeneration(), null)

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
    fun activate_givenOneFenceInTheSetIsStillAnswerable_expectItStillAsks() = runTest {
        // The second neighbour is registered only after the first request, so it carries no memo.
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
        // Tolerance is the looser error: 100 m is within the first fix's 122 m, so no move is shown.
        // 60 m is above the ceiling, so the fix reaches the escalation at all.
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
        bootSessionProvider = { "boot" },
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
     * At the venue centre, 52 m from the nearest edge, with 53 m of error: marginal, but under the
     * ~54 m ceiling. The window is only 52.7 m to 54.1 m. Same position as
     * [coarseFixInsideTheVenue], since the futile-escalation memo is keyed on position.
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

    /** 122 m of accuracy at the venue centre: reads inside but is over the arrival ceiling. */
    private fun coarseFixInsideTheVenue(
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
    ) = Location("test").apply {
        latitude = 37.7750
        longitude = -122.4194
        accuracy = 122.4f
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = 100_000L
    }

    /** Defaults to the same 122 m error, so only the position differs from [coarseFixInsideTheVenue]. */
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

    // ~111 m x 106 m, so a centre fix sits ~52 m from the nearest edge. Its `2 x area / perimeter`
    // scale is ~54 m, just above the 50 m floor, so its own depth sets the ~54 m arrival ceiling.
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
     * One precise venue-centre fix is decisive for the venue and WITHIN_ACCURACY for this ring
     * committed INSIDE. Same shape, so the same ceiling: the verdicts differ by position alone.
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
         * outside, but not clear of it at 20 m accuracy, so undecided rather than a departure.
         */
        const val HELD_CO_TENANT_SHIFT_DEGREES = 0.0002746
        const val USER_ID = "user-1"
        const val VENUE_ID = "venue"
        const val NEIGHBOUR_ID = "neighbour"
    }
}
