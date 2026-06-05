package io.customer.location

import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class LocationLifecycleObserverTest {

    private val services: LocationServicesImpl = mockk(relaxed = true)
    private val owner: LifecycleOwner = mockk(relaxed = true)

    @Test
    fun onStart_givenManualMode_expectForegroundFlushInvokedButNoLocationRequest() {
        var flushCount = 0
        val observer = LocationLifecycleObserver(
            locationServices = services,
            trackingMode = LocationTrackingMode.MANUAL,
            onForeground = { flushCount++ }
        )

        observer.onStart(owner)

        // Geofence deliveries must flush even in MANUAL mode, but MANUAL never auto-requests location.
        flushCount shouldBeEqualTo 1
        verify(exactly = 0) { services.requestLocationUpdate() }
    }

    @Test
    fun onStart_givenOnAppStart_expectForegroundFlushAndLocationRequestedOncePerForeground() {
        var flushCount = 0
        val observer = LocationLifecycleObserver(
            locationServices = services,
            trackingMode = LocationTrackingMode.ON_APP_START,
            onForeground = { flushCount++ }
        )

        observer.onStart(owner)
        observer.onStart(owner)

        // Flush fires on every foreground entry; the one-shot location request fires once.
        flushCount shouldBeEqualTo 2
        verify(exactly = 1) { services.requestLocationUpdate() }
    }

    @Test
    fun onStart_givenLocationRequestCancelledOnStop_expectFlushStillFiresOnNextForeground() {
        var flushCount = 0
        every { services.cancelInFlightRequest() } returns true
        val observer = LocationLifecycleObserver(
            locationServices = services,
            trackingMode = LocationTrackingMode.ON_APP_START,
            onForeground = { flushCount++ }
        )

        observer.onStart(owner)
        observer.onStop(owner)
        observer.onStart(owner)

        flushCount shouldBeEqualTo 2
        // hasRequestedOnStart was reset by the cancelled in-flight request, so it retries.
        verify(exactly = 2) { services.requestLocationUpdate() }
    }
}
