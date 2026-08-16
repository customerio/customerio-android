package io.customer.geofence.polygon

import android.location.Location
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
        coEvery { controller.processApproachLocations(locations, 7L) } returns true

        PolygonApproachReceiver().handleLocations(
            locations,
            7L,
            userId = "user-1",
            controller = controller,
            monitor = monitor
        )

        verify { controller.beginUserSession("user-1") }
        coVerify { controller.processApproachLocations(locations, 7L) }
        verify { monitor.start(7L) }
        verify(exactly = 0) { monitor.removeStaleGeneration(any()) }
    }

    @Test
    fun handleLocations_givenStaleUserGeneration_expectRemovesOldBackgroundRequest() = runTest {
        val locations = listOf(location())
        coEvery { controller.processApproachLocations(locations, 7L) } returns false

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

    private fun location() = Location("test").apply {
        latitude = 37.775
        longitude = -122.4194
        accuracy = 5f
        elapsedRealtimeNanos = 100L
    }
}
