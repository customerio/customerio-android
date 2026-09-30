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

    // A generation exists only once the store moves to it, so a test arming a newer one sets this.
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
        // No displacement gate, or an arrival that stopped moving is sampled once per session.
        request.captured.minUpdateDistanceMeters shouldBeEqualTo 0f
        // The OS must hold the bound too: our timer and the deadline extra die with the process.
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
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        storeUserStateGeneration = 8L

        monitor.start(8L)
        monitor.start(7L)

        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
    }

    @Test
    fun start_givenAnOlderGenerationAfterTheNewerWasStopped_expectStillRefused() {
        // stop() clears the monitor's generation, so only the store's 8L can refuse 7L.
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

        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun start_givenSignOutEndedTheGeneration_expectNoSessionForTheSignedOutUser() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        storeUserStateGeneration = 8L
        monitor.start(8L)

        // Sign-out.
        storeUserStateGeneration = 9L
        monitor.stop(8L)
        shadowOf(Looper.getMainLooper()).idle()

        monitor.start(8L)

        // Removals not counted: the request's success listener also removes the stale registration,
        // so more than one removal is correct here.
        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun start_givenTheNextUserAfterSignOut_expectTheirSessionStillArms() {
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
    fun stop_givenAColdProcessNamesADeadlineItNeverArmed_expectTheEndingIsStillReported() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val deadline = SystemClock.elapsedRealtime() + 60_000L

        monitor().start(7L, deadline)
        shadowOf(Looper.getMainLooper()).idle()
        monitor().stop(
            expectedUserStateGeneration = 7L,
            expectedSessionDeadlineElapsedRealtimeMs = deadline
        )
        shadowOf(Looper.getMainLooper()).idle()

        // Absent, not zero: zero would claim the session armed and received nothing.
        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(samplesReceived = null) }
    }

    @Test
    fun stop_givenAGenerationNeverRegistered_expectNothingRemovedAndNoStopReported() {
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)

        monitor().stop(expectedUserStateGeneration = 4_242L)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
        verify(exactly = 0) { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun stop_givenRepeatedStopsAfterTheSessionEnded_expectOneRecordNotOnePerPass() {
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
        // Not retried: a refusal is not transient.
        verify(exactly = 1) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun start_givenRequestSucceedsAfterStop_expectTheStaleRegistrationRemoved() {
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
        // One generation is one PendingIntent, so the in-flight removal lands after the restart.
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

        // Three: the first start, the restart, and the re-request the late removal success issues.
        verify(exactly = 3) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
    }

    @Test
    fun stop_givenTheEarlierRemovalLandsAfterAReArm_expectTheNewSessionStillReportsItsOwnCount() {
        // D2 arms on D1's generation (an equal PendingIntent) before D1's removal listener runs.
        // D2 gets one sample and D1 none, so one record of each attributes both.
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
    fun removeUpdates_givenTheSessionIsRestartedByTheSameSuccess_expectNoEndingReported() {
        val removal = TaskCompletionSource<Void>()
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns removal.task
        val monitor = monitor()
        val deadline = SystemClock.elapsedRealtime() + 60_000L

        monitor.start(7L, deadline)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.recordSampleDelivered(deadline)
        monitor.recordSampleDelivered(deadline)
        // Stop and re-arm on the same deadline, so the in-flight removal lands after the re-arm.
        monitor.stop()
        monitor.start(7L, deadline)
        shadowOf(Looper.getMainLooper()).idle()
        removal.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 0) { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun recordSampleDelivered_givenTheBatchArrivesBeforeTheSessionIsAdopted_expectItIsStillCounted() {
        // Production's order on a cold start: the receiver records the batch before start() adopts.
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        val deadline = SystemClock.elapsedRealtime() + 60_000L

        monitor.recordSampleDelivered(deadline)
        monitor.start(7L, deadline)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.stop(expectedUserStateGeneration = 7L, expectedSessionDeadlineElapsedRealtimeMs = deadline)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(samplesReceived = 1) }
    }

    @Test
    fun recordSampleDelivered_givenTheSessionAlreadyEnded_expectItCannotBuyASecondEnding() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<PendingIntent>()) } returns Tasks.forResult(null)
        val monitor = monitor()
        val deadline = SystemClock.elapsedRealtime() + 60_000L

        monitor.start(7L, deadline)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.recordSampleDelivered(deadline)
        monitor.stop(expectedUserStateGeneration = 7L, expectedSessionDeadlineElapsedRealtimeMs = deadline)
        shadowOf(Looper.getMainLooper()).idle()
        monitor.recordSampleDelivered(deadline)
        monitor.stop(expectedUserStateGeneration = 7L, expectedSessionDeadlineElapsedRealtimeMs = deadline)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(samplesReceived = 1) }
        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(any()) }
    }

    @Test
    fun start_givenAPartlySpentSession_expectOnlyTheRemainingBudgetOnTheRequest() {
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
        // Identity is the generation alone, so this re-arm builds an equal PendingIntent; removing
        // it would cancel the request about to be made.
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
        // The first session did end: one record, pinned to zero since nothing was delivered.
        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(0) }
    }

    @Test
    fun stop_givenRemovalRetriedThenStaleStop_expectOnlyOneEndingReported() {
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
        val deadline = SystemClock.elapsedRealtime() + 60_000L

        monitor.start(7L, deadline)
        shadowOf(Looper.getMainLooper()).idle()
        repeat(3) { monitor.recordSampleDelivered(deadline) }
        monitor.stop(7L, deadline)
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.advanceTimeBy(5_001L)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        monitor.stop(7L, deadline)
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { logger.logPolygonApproachMonitoringStopped(3) }
        verify(exactly = 0) { logger.logPolygonApproachMonitoringStopped(null) }
    }

    private fun monitor() = PolygonApproachMonitor(
        context = applicationMock,
        client = client,
        store = mockStore,
        logger = logger,
        backgroundContext = Dispatchers.Unconfined
    )
}
