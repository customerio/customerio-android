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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PolygonApproachMonitorTest : RobolectricTest() {
    private val client: FusedLocationProviderClient = mockk(relaxed = true)
    private val logger: GeofenceLogger = mockk(relaxed = true)

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
        request.captured.minUpdateDistanceMeters shouldBeEqualTo 25f
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
        monitor.start(8L)

        verify(exactly = 2) {
            client.requestLocationUpdates(any<LocationRequest>(), any<PendingIntent>())
        }
        verify(exactly = 1) { client.removeLocationUpdates(any<PendingIntent>()) }
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
        verify { logger.logPolygonApproachMonitoringStopped() }
    }

    @Test
    fun stop_givenColdProcessGeneration_expectReconstructsAndRemovesPendingIntent() {
        val pendingIntent = slot<PendingIntent>()
        every { client.removeLocationUpdates(capture(pendingIntent)) } returns Tasks.forResult(null)
        val monitor = monitor()

        monitor.stop(expectedUserStateGeneration = 7L)
        shadowOf(Looper.getMainLooper()).idle()

        shadowOf(pendingIntent.captured).savedIntent.getLongExtra(
            PolygonApproachMonitor.EXTRA_USER_STATE_GENERATION,
            -1L
        ) shouldBeEqualTo 7L
        verify { logger.logPolygonApproachMonitoringStopped() }
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
            logger = logger,
            backgroundContext = StandardTestDispatcher(scheduler)
        )

        monitor.start(7L)
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.advanceTimeBy(180_000L)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 0) { client.removeLocationUpdates(any<PendingIntent>()) }
        verify(exactly = 0) { logger.logPolygonApproachMonitoringStopped() }
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
        verify(exactly = 0) { logger.logPolygonApproachMonitoringStopped() }
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

    private fun monitor() = PolygonApproachMonitor(
        context = applicationMock,
        client = client,
        logger = logger,
        backgroundContext = Dispatchers.Unconfined
    )
}
