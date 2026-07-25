package io.customer.location

import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class LocationLifecycleObserverTest {

    private val services: LocationServicesImpl = mockk(relaxed = true)
    private val owner: LifecycleOwner = mockk(relaxed = true)

    @Test
    fun onStart_givenManualMode_expectNoLocationRequest() {
        val observer = LocationLifecycleObserver(
            locationServices = services,
            trackingMode = LocationTrackingMode.MANUAL
        )

        observer.onStart(owner)

        verify(exactly = 0) { services.requestLocationUpdate() }
    }

    @Test
    fun onStart_givenOnAppStart_expectLocationRequestedOncePerProcess() {
        val observer = LocationLifecycleObserver(
            locationServices = services,
            trackingMode = LocationTrackingMode.ON_APP_START
        )

        observer.onStart(owner)
        observer.onStart(owner)

        verify(exactly = 1) { services.requestLocationUpdate() }
    }

    @Test
    fun onStop_givenInFlightRequestCancelled_expectNextForegroundReFiresLocationRequest() {
        every { services.cancelInFlightRequest() } returns true
        val observer = LocationLifecycleObserver(
            locationServices = services,
            trackingMode = LocationTrackingMode.ON_APP_START
        )

        observer.onStart(owner)
        observer.onStop(owner)
        observer.onStart(owner)

        // hasRequestedOnStart was reset by the cancelled in-flight request, so it retries.
        verify(exactly = 2) { services.requestLocationUpdate() }
    }
}
