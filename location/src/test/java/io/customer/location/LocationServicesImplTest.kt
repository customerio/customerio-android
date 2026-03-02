package io.customer.location

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationServicesImplTest {

    // -- Mode OFF tests --

    @Test
    fun givenModeOff_setLastKnownLocation_expectNoOp() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.OFF)
            .build()
        val syncCoordinator: LocationSyncCoordinator = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, syncCoordinator, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify(exactly = 0) { syncCoordinator.onLocationReceived(any(), any()) }
    }

    @Test
    fun givenModeManual_setLastKnownLocation_expectSyncCoordinatorCalled() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.MANUAL)
            .build()
        val syncCoordinator: LocationSyncCoordinator = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, syncCoordinator, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify { syncCoordinator.onLocationReceived(37.7749, -122.4194) }
    }

    @Test
    fun givenModeOnAppStart_setLastKnownLocation_expectSyncCoordinatorCalled() {
        val config = LocationModuleConfig.Builder()
            .setLocationTrackingMode(LocationTrackingMode.ON_APP_START)
            .build()
        val syncCoordinator: LocationSyncCoordinator = mockk(relaxUnitFun = true)
        val orchestrator: LocationOrchestrator = mockk(relaxUnitFun = true)
        val logger = mockk<io.customer.sdk.core.util.Logger>(relaxUnitFun = true)
        val scope = TestScope(UnconfinedTestDispatcher())

        val services = LocationServicesImpl(config, logger, syncCoordinator, orchestrator, scope)
        services.setLastKnownLocation(37.7749, -122.4194)

        verify { syncCoordinator.onLocationReceived(37.7749, -122.4194) }
    }

    // -- Coordinate validation tests --

    @Test
    fun isValidCoordinate_givenValidCoordinates_expectTrue() {
        LocationServicesImpl.isValidCoordinate(37.7749, -122.4194).shouldBeTrue()
    }

    @Test
    fun isValidCoordinate_givenBoundaryValues_expectTrue() {
        LocationServicesImpl.isValidCoordinate(90.0, 180.0).shouldBeTrue()
        LocationServicesImpl.isValidCoordinate(-90.0, -180.0).shouldBeTrue()
        LocationServicesImpl.isValidCoordinate(0.0, 0.0).shouldBeTrue()
    }

    @Test
    fun isValidCoordinate_givenLatitudeOutOfRange_expectFalse() {
        LocationServicesImpl.isValidCoordinate(91.0, 0.0).shouldBeFalse()
        LocationServicesImpl.isValidCoordinate(-91.0, 0.0).shouldBeFalse()
    }

    @Test
    fun isValidCoordinate_givenLongitudeOutOfRange_expectFalse() {
        LocationServicesImpl.isValidCoordinate(0.0, 181.0).shouldBeFalse()
        LocationServicesImpl.isValidCoordinate(0.0, -181.0).shouldBeFalse()
    }

    @Test
    fun isValidCoordinate_givenNaN_expectFalse() {
        LocationServicesImpl.isValidCoordinate(Double.NaN, 0.0).shouldBeFalse()
        LocationServicesImpl.isValidCoordinate(0.0, Double.NaN).shouldBeFalse()
    }

    @Test
    fun isValidCoordinate_givenInfinity_expectFalse() {
        LocationServicesImpl.isValidCoordinate(Double.POSITIVE_INFINITY, 0.0).shouldBeFalse()
        LocationServicesImpl.isValidCoordinate(0.0, Double.NEGATIVE_INFINITY).shouldBeFalse()
    }
}
