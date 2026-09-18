package io.customer.geofence.polygon

import android.app.PendingIntent
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.store.GeofenceRegionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonApproachMonitorTest : RobolectricTest() {
    private val client: FusedLocationProviderClient = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)

    // The live generation as the store reports it. A generation only exists once the store has
    // moved to it, so a test arming a newer one moves this first.
    private var storeUserStateGeneration = 7L
    private val mockStore: GeofenceRegionStore = mockk(relaxed = true) {
        every { userStateGeneration() } answers { storeUserStateGeneration }
    }

    @Test
    fun start_expectBoundedResponsiveRequestBoundToUserGeneration() {
        val request = slot<LocationRequest>()
        val pendingIntent = slot<PendingIntent>()
        every {
            client.requestLocationUpdates(capture(request), capture(pendingIntent))
        } returns Tasks.forResult(null)
        val monitor = monitor()

        monitor.start(expectedUserStateGeneration = 7L)
        shadowOf(Looper.getMainLooper()).idle()

        request.captured.priority shouldBeEqualTo Priority.PRIORITY_BALANCED_POWER_ACCURACY
        request.captured.intervalMillis shouldBeEqualTo 15_000L
        request.captured.minUpdateIntervalMillis shouldBeEqualTo 5_000L
        // No displacement gate. The case this session exists to judge is an arrival that has
        // already stopped moving, and a 25 m gate let such a device be sampled once per session.
        request.captured.minUpdateDistanceMeters shouldBeEqualTo 0f
        // The OS has to hold the bound too. Our timer and the deadline extra both die with the
        // process, while this PendingIntent registration survives it.
        request.captured.durationMillis shouldBeEqualTo 2 * 60_000L
        shadowOf(pendingIntent.captured).savedIntent.getLongExtra(
            PolygonApproachMonitor.EXTRA_USER_STATE_GENERATION,
            -1L
        ) shouldBeEqualTo 7L
        verify { logger.logPolygonApproachMonitoringStarted() }
    }

    @Test
    fun start_givenSameGenerationTwice_expectSingleRegistration() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        val monitor = monitor()

        monitor.start(7L)
        monitor.start(7L)

        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun start_givenNewUserGeneration_expectRemovesOldRequestBeforeRegisteringNewOne() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()

        monitor.start(7L)
        storeUserStateGeneration = 8L
        monitor.start(8L)

        verify(exactly = 2) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
        verify(exactly = 1) { client.removeLocationUpdates(any<PendingIntent>()) }
    }

    @Test
    fun start_givenAnOlderGeneration_expectTheNewerRequestKept() {
        // A receiver preempted after deciding CONTINUE resumes holding the generation it captured
        // before suspending. By then an identify and a fresh polygon wake can have armed a newer
        // session, and arming the older one would replace that request. Its own timeout removes the
        // replacement later but does not restore what it displaced.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        storeUserStateGeneration = 8L

        monitor.start(8L)
        monitor.start(7L)

        // Only the newer session registered, and nothing was torn down to make room for the older.
        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
    }

    @Test
    fun start_givenAnOlderGenerationAfterTheNewerWasStopped_expectStillRefused() {
        // `stop` clears the live generation, so the monitor's own state cannot tell a stale
        // caller from a first one. The store still names the live generation, so an ended one
        // stays ended even with nothing armed.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        storeUserStateGeneration = 8L

        monitor.start(8L)
        monitor.stop()
        shadowOf(Looper.getMainLooper()).idle()
        monitor.start(7L)

        // The 8L registration and its teardown only; 7L never armed.
        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun start_givenSignOutEndedTheGeneration_expectNoSessionForTheSignedOutUser() {
        // Sign-out ends a generation without arming a newer one, so nothing this monitor holds
        // says it is over: `stop` clears the live generation, and the generation that replaced it
        // exists only in the store. A receiver preempted between deciding to sample and arriving
        // here resumes with the old one still in hand, and an equal-generation restart would open
        // a fine-location session for a user who has signed out.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        storeUserStateGeneration = 8L
        monitor.start(8L)

        // Sign-out: the store moves past 8, and the session it armed is torn down by name.
        storeUserStateGeneration = 9L
        monitor.stop(8L)
        shadowOf(Looper.getMainLooper()).idle()

        monitor.start(8L)

        // The first session and nothing more. Removals are not counted here: the request made
        // before sign-out completes after it, and its own success listener tears down the
        // registration it finds stale, so more than one removal is correct on this path.
        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun start_givenTheNextUserAfterSignOut_expectTheirSessionStillArms() {
        // Control for the refusal above: the guard must reject the generation the store has left
        // behind, not every caller that follows a sign-out.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        storeUserStateGeneration = 8L
        monitor.start(8L)
        storeUserStateGeneration = 9L
        monitor.stop(8L)
        shadowOf(Looper.getMainLooper()).idle()

        monitor.start(9L)

        verify(exactly = 2) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun stop_expectRemovesPendingIntentRegistration() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()

        monitor.start(7L)
        monitor.stop()
        shadowOf(Looper.getMainLooper()).idle()

        verify(atLeast = 1) { client.removeLocationUpdates(any<PendingIntent>()) }
        verify { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun stop_givenColdProcessGeneration_expectRemovesWhatTheEarlierProcessRegistered() {
        // The registration outlives the process that made it, so a monitor holding none of that
        // state can still tear it down by naming the generation.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        val pendingIntent = slot<PendingIntent>()
        every { client.removeLocationUpdates(capture(pendingIntent)) } returns Tasks.forResult(null)

        monitor().start(7L)
        shadowOf(Looper.getMainLooper()).idle()
        monitor().stop(expectedUserStateGeneration = 7L)
        shadowOf(Looper.getMainLooper()).idle()

        shadowOf(pendingIntent.captured).savedIntent.getLongExtra(
            PolygonApproachMonitor.EXTRA_USER_STATE_GENERATION,
            -1L
        ) shouldBeEqualTo 7L
        verify { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun stop_givenAGenerationNeverRegistered_expectNothingRemovedAndNoStopReported() {
        // Asking the OS to remove a request that was never made used to MINT the PendingIntent,
        // because FLAG_UPDATE_CURRENT creates when none exists, and then report a teardown for it.
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)

        monitor().stop(expectedUserStateGeneration = 4_242L)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
        verify(exactly = 0) { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun stop_givenRepeatedStopsAfterTheSessionEnded_expectOneRecordNotOnePerPass() {
        // The 2026-09-18 field case. Once any session has built a PendingIntent for the live
        // generation, nothing cancels it, so every later sync pass that calls stop gets a success
        // back from GMS for a request no longer attached. That filled the capture with fourteen
        // teardowns against two real sessions, at two per pass, which is the record we rely on to
        // tell whether the approach session ever sampled.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()

        monitor.start(7L)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.stop(expectedUserStateGeneration = 7L)
        shadowOf(Looper.getMainLooper()).idle()
        repeat(4) {
            monitor.stop(expectedUserStateGeneration = 7L)
            shadowOf(Looper.getMainLooper()).idle()
        }

        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun stop_givenTheSessionReArmedBetweenTeardowns_expectBothReported() {
        // The control for the memo above: it must suppress a repeat of the same removal, not a
        // second genuine teardown. Arming again is what makes the next removal real.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()

        repeat(2) {
            monitor.start(7L)
            shadowOf(Looper.getMainLooper()).idle()
            monitor.stop(expectedUserStateGeneration = 7L)
            shadowOf(Looper.getMainLooper()).idle()
        }

        verify(exactly = 2) { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun start_givenExistingDeadline_expectCarriesItAcrossProcessDelivery() {
        val pendingIntent = slot<PendingIntent>()
        every {
            client.requestLocationUpdates(any<LocationRequest>(), capture(pendingIntent))
        } returns Tasks.forResult(null)
        val monitor = monitor()
        val deadline = android.os.SystemClock.elapsedRealtime() + 60_000L

        monitor.start(7L, deadline)

        shadowOf(pendingIntent.captured).savedIntent.getLongExtra(
            PolygonApproachMonitor.EXTRA_SESSION_DEADLINE_ELAPSED_REALTIME_MS,
            -1L
        ) shouldBeEqualTo deadline
    }

    @Test
    fun stop_givenExpiredBatchFromOlderSession_expectKeepsCurrentSameUserSession() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        val currentDeadline = android.os.SystemClock.elapsedRealtime() + 60_000L

        monitor.start(7L, currentDeadline)
        monitor.stop(7L, expectedSessionDeadlineElapsedRealtimeMs = currentDeadline - 1L)

        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
    }

    @Test
    fun start_givenTransientRegistrationFailure_expectRetriesCurrentUserGeneration() {
        var attempts = 0
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } answers {
            attempts += 1
            if (attempts == 1) {
                Tasks.forException(IllegalStateException("temporarily unavailable"))
            } else {
                Tasks.forResult(null)
            }
        }
        val scheduler = TestCoroutineScheduler()
        val monitor = PolygonApproachMonitor(
            context = applicationMock,
            client = client,
            store = mockStore,
            logger = logger,
            backgroundContext = StandardTestDispatcher(scheduler)
        )

        monitor.start(7L)
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.advanceTimeBy(5_000L)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 2) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
        verify { logger.logPolygonApproachMonitoringStarted() }
    }

    @Test
    fun start_givenNoDecisiveFix_expectSessionStopsAfterTwoMinutes() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val scheduler = TestCoroutineScheduler()
        val monitor = PolygonApproachMonitor(
            context = applicationMock,
            client = client,
            store = mockStore,
            logger = logger,
            backgroundContext = StandardTestDispatcher(scheduler)
        )

        monitor.start(7L)
        scheduler.advanceTimeBy(120_000L)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        verify { client.removeLocationUpdates(any<PendingIntent>()) }
    }

    @Test
    fun stop_givenGenerationOfAnAlreadySupersededSession_expectLiveSessionUntouched() {
        // Callers read the generation, then reach stop with no lock held. An identify landing in
        // that gap must not let the older caller tear down the session it does not own.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()

        storeUserStateGeneration = 8L
        monitor.start(8L)
        monitor.stop(expectedUserStateGeneration = 7L)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
    }

    @Test
    fun start_givenSecurityException_expectSessionAbandonedAndItsTimerCancelled() {
        // Nothing is registered after a permission refusal, so a surviving timer would later remove
        // a request that never existed and report a stop that did not happen.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forException(SecurityException("location permission revoked"))
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val scheduler = TestCoroutineScheduler()
        val monitor = PolygonApproachMonitor(
            context = applicationMock,
            client = client,
            store = mockStore,
            logger = logger,
            backgroundContext = StandardTestDispatcher(scheduler)
        )

        monitor.start(7L)
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.advanceTimeBy(180_000L)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
        verify(exactly = 0) { logger.logPolygonApproachMonitoringStopped(any()) }
        // Never retried either: a refusal is not transient.
        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun start_givenRequestSucceedsAfterStop_expectTheStaleRegistrationRemoved() {
        // The OS answers late. By then the session is over, so leaving it registered would stream
        // locations for a user who is gone.
        val pending = TaskCompletionSource<Void>()
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns pending.task
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()

        monitor.start(7L)
        monitor.stop()
        shadowOf(Looper.getMainLooper()).idle()
        pending.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()

        verify(atLeast = 1) { client.removeLocationUpdates(any<PendingIntent>()) }
        verify(exactly = 0) { logger.logPolygonApproachMonitoringStarted() }
    }

    @Test
    fun start_givenTransientRegistrationFailure_expectTheOperationNamedOnTheRecord() {
        // `op` is what separates this from a failed removal; both share the record.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forException(IllegalStateException("temporarily unavailable"))
        val monitor = monitor()

        monitor.start(7L)
        shadowOf(Looper.getMainLooper()).idle()

        verify { logger.logPolygonApproachRequestFailed(any(), "request_updates") }
    }

    @Test
    fun stop_givenRemovalFailure_expectTheRemovalOperationNamedOnTheRecord() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every {
            client.removeLocationUpdates(any<PendingIntent>())
        } returns Tasks.forException(IllegalStateException("remove boom"))
        val monitor = monitor()

        monitor.start(7L)
        monitor.stop()
        shadowOf(Looper.getMainLooper()).idle()

        verify { logger.logPolygonApproachRequestFailed(any(), "remove_updates") }
        verify(exactly = 0) { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun stop_givenRemovalFailsThenSucceeds_expectRetriedAndNotReRequested() {
        // A stale registration that fails to be removed keeps streaming locations for a session
        // that is over, so the removal has to be retried. It must not turn into a new request.
        var removals = 0
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } answers {
            removals += 1
            if (removals == 1) {
                Tasks.forException(IllegalStateException("remove boom"))
            } else {
                Tasks.forResult(null)
            }
        }
        val scheduler = TestCoroutineScheduler()
        val monitor = PolygonApproachMonitor(
            context = applicationMock,
            client = client,
            store = mockStore,
            logger = logger,
            backgroundContext = StandardTestDispatcher(scheduler)
        )

        monitor.start(7L)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.stop()
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.advanceTimeBy(5_001L)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        removals shouldBeEqualTo 2
        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun startAfterStop_givenTheRemovalSucceedsLate_expectTheSessionReRequested() {
        // stop() then start() on the same generation is one PendingIntent, so the in-flight removal
        // lands after the restart. Ignoring that leaves the session wanted but unregistered.
        val removal = TaskCompletionSource<Void>()
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns removal.task
        val monitor = monitor()

        monitor.start(7L)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.stop()
        monitor.start(7L)
        shadowOf(Looper.getMainLooper()).idle()
        removal.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()

        // Three: the first start, the restart, and the one the late removal success issues because
        // the session is wanted again under the same PendingIntent. Without that third the session
        // stays wanted but unregistered.
        verify(exactly = 3) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun stop_givenTheEarlierRemovalLandsAfterAReArm_expectTheNewSessionStillReportsItsOwnCount() {
        // The ABA across a re-arm. D1 is stopped, D2 arms on the same generation before D1's
        // removal listener runs, and the two sessions build equal PendingIntents. While a
        // one-slot memo of the last removed intent gated every teardown, D1's late listener wrote
        // that intent back after D2 had armed, so D2's real teardown was suppressed as a repeat
        // and its sample count was never reported.
        //
        // The counts are what name the sessions here: D2 delivered a sample and D1 did not, so
        // one record of each is the only outcome that attributes both correctly.
        val firstDeadline = SystemClock.elapsedRealtime() + 60_000L
        val secondDeadline = SystemClock.elapsedRealtime() + 120_000L
        val firstRemoval = TaskCompletionSource<Void>()
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        var removals = 0
        every { client.removeLocationUpdates(any<PendingIntent>()) } answers {
            removals += 1
            if (removals == 1) firstRemoval.task else Tasks.forResult(null)
        }
        val monitor = monitor()

        monitor.start(7L, firstDeadline)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.stop(
            expectedUserStateGeneration = 7L,
            expectedSessionDeadlineElapsedRealtimeMs = firstDeadline
        )
        shadowOf(Looper.getMainLooper()).idle()
        monitor.start(7L, secondDeadline)
        monitor.recordSampleDelivered(secondDeadline)
        shadowOf(Looper.getMainLooper()).idle()
        firstRemoval.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.stop(
            expectedUserStateGeneration = 7L,
            expectedSessionDeadlineElapsedRealtimeMs = secondDeadline
        )
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(samplesReceived = 0) }
        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(samplesReceived = 1) }
    }

    @Test
    fun start_givenAPartlySpentSession_expectOnlyTheRemainingBudgetOnTheRequest() {
        // A retry or a restart mid-session must not hand the OS a fresh two minutes, or a session
        // that keeps failing to register renews its own bound every attempt.
        val request = slot<LocationRequest>()
        every {
            client.requestLocationUpdates(capture(request), any<PendingIntent>())
        } returns Tasks.forResult(null)
        val monitor = monitor()

        monitor.start(
            expectedUserStateGeneration = 7L,
            sessionDeadlineElapsedRealtimeMs = SystemClock.elapsedRealtime() + 30_000L
        )
        shadowOf(Looper.getMainLooper()).idle()

        request.captured.durationMillis shouldBeLessOrEqualTo 30_000L
        request.captured.durationMillis shouldBeGreaterThan 25_000L
    }

    @Test
    fun start_givenTheSameGenerationAfterItsDeadlinePassed_expectOneRegistrationAndNoFalseStop() {
        // Identity is the generation alone, so a re-arm for the same generation builds an EQUAL
        // PendingIntent. Removing "the previous one" therefore cancels the request this very call
        // is about to make, and the removal's success listener re-requests it and logs a teardown
        // that never happened — a tail a replay grading on polygon.approach.* reads as real.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        // Never advanced, so the session timeout never fires and the expired session stays armed.
        val scheduler = TestCoroutineScheduler()
        val monitor = PolygonApproachMonitor(
            context = applicationMock,
            client = client,
            store = mockStore,
            logger = logger,
            backgroundContext = StandardTestDispatcher(scheduler)
        )

        monitor.start(7L, SystemClock.elapsedRealtime() + 30_000L)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowSystemClock.advanceBy(Duration.ofMillis(31_000L))
        monitor.start(7L, SystemClock.elapsedRealtime() + 30_000L)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 2) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
        // The first session did end, so its tally is owed exactly one record, and it is pinned to
        // zero rather than any(): the count is the whole point of the record, and nothing was
        // delivered against that deadline. Skipping the removal above is what used to strand it,
        // leaving the entry in the map and the finding unreported.
        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(0) }
    }

    private fun monitor() = PolygonApproachMonitor(
        context = applicationMock,
        client = client,
        store = mockStore,
        logger = logger,
        backgroundContext = Dispatchers.Unconfined
    )
}
