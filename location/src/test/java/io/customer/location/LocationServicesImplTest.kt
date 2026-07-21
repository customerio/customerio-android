package io.customer.location

import io.customer.base.internal.InternalCustomerIOApi
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCustomerIOApi::class)
class LocationServicesImplTest {

    // -- Mode OFF tests --

    @Test
    fun givenModeOff_setLastKnownLocation_expectNoOp() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.OFF)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify(exactly = 0) { tracker.onLocationReceived(any(), any()) }
    }

    @Test
    fun givenModeManual_setLastKnownLocation_expectTrackerCalled() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.MANUAL)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify { tracker.onLocationReceived(37.7749, -122.4194) }
    }

    @Test
    fun givenModeOnAppStart_setLastKnownLocation_expectTrackerCalled() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.ON_APP_START)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify { tracker.onLocationReceived(37.7749, -122.4194) }
    }

    // -- requestLocationUpdateSilently --

    @Test
    fun requestLocationUpdateSilently_expectSilentOrchestratorCall() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.MANUAL)
            .build()
        val tracker: LocationTracker = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, tracker, orchestrator, scope)
        services.requestLocationUpdateSilently()

        coVerify { orchestrator.requestLocationUpdateSilently() }
        coVerify(exactly = 0) { orchestrator.requestLocationUpdate() }
    }
}
