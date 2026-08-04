package io.customer.geofence

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.location.LocationCoordinates
import io.customer.location.LocationServices
import io.customer.location.ModuleLocation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

@OptIn(InternalCustomerIOApi::class)
class ModuleGeofenceTest {

    private val mockLocationServices: LocationServices = mockk(relaxed = true)
    private val mockLocationModule: ModuleLocation = mockk {
        every { locationServices } returns mockLocationServices
    }

    private fun moduleWith(mode: GeofenceLocationMode) =
        ModuleGeofence(GeofenceModuleConfig.Builder().setLocationMode(mode).build())

    @Test
    fun autoAcquireIfNeeded_givenNoLocationAndAutomatic_expectSilentFetch() {
        moduleWith(GeofenceLocationMode.AUTOMATIC).autoAcquireIfNeeded(mockLocationModule, currentLocation = null)

        verify { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun autoAcquireIfNeeded_givenNoLocationAndManual_expectNoFetch() {
        moduleWith(GeofenceLocationMode.MANUAL).autoAcquireIfNeeded(mockLocationModule, currentLocation = null)

        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun autoAcquireIfNeeded_givenLocationAlreadyAvailable_expectNoFetch() {
        moduleWith(GeofenceLocationMode.AUTOMATIC)
            .autoAcquireIfNeeded(mockLocationModule, currentLocation = LocationCoordinates(latitude = 1.0, longitude = 2.0))

        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun refreshOnForeground_givenAutomatic_expectArmedAndSilentFetch() {
        val mockServices: GeofenceServices = mockk(relaxed = true)

        moduleWith(GeofenceLocationMode.AUTOMATIC)
            .refreshOnForeground(mockServices, mockLocationModule) shouldBeEqualTo true

        // Arming after the request would race the fix and drop it.
        verifyOrder {
            mockServices.onRefreshRequested()
            mockLocationServices.requestLocationUpdateSilently()
        }
    }

    @Test
    fun refreshOnForeground_givenManual_expectNoFetchOrArm() {
        val mockServices: GeofenceServices = mockk(relaxed = true)

        moduleWith(GeofenceLocationMode.MANUAL)
            .refreshOnForeground(mockServices, mockLocationModule) shouldBeEqualTo false

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun refreshOnForeground_givenSyncAlreadyAwaitingLocation_expectDeferredToStuckSyncPath() {
        val mockServices: GeofenceServices = mockk(relaxed = true) {
            every { isAwaitingLocation() } returns true
        }

        moduleWith(GeofenceLocationMode.AUTOMATIC)
            .refreshOnForeground(mockServices, mockLocationModule) shouldBeEqualTo false

        verify(exactly = 0) { mockServices.onRefreshRequested() }
        verify(exactly = 0) { mockLocationServices.requestLocationUpdateSilently() }
    }

    @Test
    fun resolveAnchor_givenRegistrationCenter_expectItPreferredOverLastKnown() {
        val anchor = moduleWith(GeofenceLocationMode.AUTOMATIC).resolveAnchor(
            registrationCenter = GeofenceLocation(latitude = 10.0, longitude = 20.0),
            lastKnown = LocationCoordinates(latitude = 1.0, longitude = 2.0)
        )

        anchor shouldBeEqualTo LocationCoordinates(latitude = 10.0, longitude = 20.0)
    }

    @Test
    fun resolveAnchor_givenNoRegistrationCenter_expectFallsBackToLastKnown() {
        val anchor = moduleWith(GeofenceLocationMode.AUTOMATIC).resolveAnchor(
            registrationCenter = null,
            lastKnown = LocationCoordinates(latitude = 1.0, longitude = 2.0)
        )

        anchor shouldBeEqualTo LocationCoordinates(latitude = 1.0, longitude = 2.0)
    }

    @Test
    fun resolveAnchor_givenNeither_expectNull() {
        moduleWith(GeofenceLocationMode.AUTOMATIC)
            .resolveAnchor(registrationCenter = null, lastKnown = null)
            .shouldBeNull()
    }
}
