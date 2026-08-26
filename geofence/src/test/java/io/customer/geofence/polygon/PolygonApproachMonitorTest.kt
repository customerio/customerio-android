package io.customer.geofence.polygon

import android.app.PendingIntent
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
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

    private fun monitor() = PolygonApproachMonitor(
        context = applicationMock,
        client = client,
        logger = logger,
        backgroundContext = Dispatchers.Unconfined
    )
}
