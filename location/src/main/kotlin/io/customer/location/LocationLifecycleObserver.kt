package io.customer.location

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Cancels in-flight location requests when the app enters background.
 *
 * Registered with [ProcessLifecycleOwner] during module initialization.
 * [onStop] fires when the app transitions to background — any active
 * GPS request is cancelled to avoid unnecessary work while backgrounded.
 *
 * No mutable state — thread safety is inherent.
 */
internal class LocationLifecycleObserver(
    private val locationServices: LocationServicesImpl
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        locationServices.cancelInFlightRequest()
    }
}
