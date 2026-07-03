package io.customer.location

import io.customer.location.provider.LocationProvider
import io.customer.location.type.AuthorizationStatus
import io.customer.location.type.LocationGranularity
import io.customer.location.type.LocationSnapshot
import io.customer.sdk.core.util.Logger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationOrchestratorTest {

    private val logger = mockk<Logger>(relaxUnitFun = true)
    private val tracker = mockk<LocationTracker>(relaxUnitFun = true)
    private val provider = mockk<LocationProvider>()

    private fun orchestrator(mode: LocationTrackingMode) = LocationOrchestrator(
        config = LocationModuleConfig.Builder().setLocationTrackingMode(mode).build(),
        logger = logger,
        locationTracker = tracker,
        locationProvider = provider
    )

    private fun givenAuthorizedFix() {
        coEvery { provider.currentAuthorizationStatus() } returns AuthorizationStatus.AUTHORIZED_FOREGROUND
        coEvery { provider.requestLocation(LocationGranularity.DEFAULT) } returns LocationSnapshot(
            latitude = 37.7749,
            longitude = -122.4194,
            timestamp = Date(),
            horizontalAccuracy = 10.0
        )
    }

    @Test
    fun requestLocationUpdate_givenModeOff_expectNoFix() = runTest {
        orchestrator(LocationTrackingMode.OFF).requestLocationUpdate()

        coVerify(exactly = 0) { provider.requestLocation(any()) }
        verify(exactly = 0) { tracker.onLocationReceived(any(), any()) }
    }

    @Test
    fun requestLocationUpdate_givenModeEnabledAndAuthorized_expectTrackedFix() = runTest {
        givenAuthorizedFix()

        orchestrator(LocationTrackingMode.MANUAL).requestLocationUpdate()

        verify { tracker.onLocationReceived(37.7749, -122.4194) }
        verify(exactly = 0) { tracker.onLocationReceivedWithoutTracking(any(), any()) }
    }

    @Test
    fun requestLocationUpdateSilently_givenModeOff_expectFixWithoutTracking() = runTest {
        givenAuthorizedFix()

        orchestrator(LocationTrackingMode.OFF).requestLocationUpdateSilently()

        coVerify { provider.requestLocation(LocationGranularity.DEFAULT) }
        verify { tracker.onLocationReceivedWithoutTracking(37.7749, -122.4194) }
        verify(exactly = 0) { tracker.onLocationReceived(any(), any()) }
    }

    @Test
    fun requestLocationUpdateSilently_givenPermissionDenied_expectNoFix() = runTest {
        coEvery { provider.currentAuthorizationStatus() } returns AuthorizationStatus.DENIED

        orchestrator(LocationTrackingMode.OFF).requestLocationUpdateSilently()

        coVerify(exactly = 0) { provider.requestLocation(any()) }
        verify(exactly = 0) { tracker.onLocationReceivedWithoutTracking(any(), any()) }
    }
}
