package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import io.customer.commontest.core.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PolygonApproachReceiverTest : RobolectricTest() {
    private val controller: PolygonGeofenceServiceController = mockk(relaxed = true)
    private val monitor: PolygonApproachMonitor = mockk(relaxed = true)

    @Test
    fun handleLocations_givenCurrentUserGeneration_expectRoutesFixesToController() = runTest {
        val locations = listOf(location())
        coEvery {
            controller.processApproachLocations(locations, 7L)
        } returns PolygonSamplingDecision.CONTINUE

        PolygonApproachReceiver().handleLocations(
            locations,
            7L,
            sessionDeadlineElapsedRealtimeMs = Long.MAX_VALUE,
            userId = "user-1",
            controller = controller,
            monitor = monitor
        )

        verify { controller.beginUserSession("user-1") }
        coVerify { controller.processApproachLocations(locations, 7L) }
        verify { monitor.start(7L, Long.MAX_VALUE) }
        verify(exactly = 0) { monitor.removeStaleGeneration(any()) }
    }

    @Test
    fun handleLocations_givenStaleUserGeneration_expectRemovesOldBackgroundRequest() = runTest {
        val locations = listOf(location())
        coEvery {
            controller.processApproachLocations(locations, 7L)
        } returns PolygonSamplingDecision.STALE

        PolygonApproachReceiver().handleLocations(
            locations,
            7L,
            userId = "user-2",
            controller = controller,
            monitor = monitor
        )

        verify { monitor.removeStaleGeneration(7L) }
    }

    @Test
    fun handleLocations_givenAnonymousSession_expectRemovesBackgroundRequestWithoutLocationWork() = runTest {
        val locations = listOf(location())

        PolygonApproachReceiver().handleLocations(
            locations,
            7L,
            userId = null,
            controller = controller,
            monitor = monitor
        )

        coVerify(exactly = 0) { controller.processApproachLocations(any(), any()) }
        verify { monitor.removeStaleGeneration(7L) }
    }

    @Test
    fun handleLocations_givenExpiredSession_expectEvaluatesDeliveredFixThenRemovesMatchingRequest() = runTest {
        val locations = listOf(location())
        coEvery {
            controller.processApproachLocations(locations, 7L)
        } returns PolygonSamplingDecision.CONTINUE
        val deadline = SystemClock.elapsedRealtime()

        PolygonApproachReceiver().handleLocations(
            locations,
            7L,
            sessionDeadlineElapsedRealtimeMs = deadline,
            userId = "user-1",
            controller = controller,
            monitor = monitor
        )

        coVerify { controller.processApproachLocations(locations, 7L) }
        verify { monitor.stop(7L, deadline) }
    }

    @Test
    fun handleLocations_givenSafeDecision_expectColdProcessRequestIsRemoved() = runTest {
        val locations = listOf(location())
        coEvery {
            controller.processApproachLocations(locations, 7L)
        } returns PolygonSamplingDecision.STOP

        PolygonApproachReceiver().handleLocations(
            locations,
            7L,
            sessionDeadlineElapsedRealtimeMs = Long.MAX_VALUE,
            userId = "user-1",
            controller = controller,
            monitor = monitor
        )

        verify { monitor.stop(7L, Long.MAX_VALUE) }
    }

    private fun location() = Location("test").apply {
        latitude = 37.775
        longitude = -122.4194
        accuracy = 5f
        elapsedRealtimeNanos = 100L
    }
}
