package io.customer.location

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/**
 * Manages location lifecycle tied to app foreground/background transitions.
 *
 * Registered with [ProcessLifecycleOwner] during module initialization.
 * - [onStart]: triggers a one-shot location request on the first foreground entry
 *   when [trackingMode] is [LocationTrackingMode.ON_APP_START].
 * - [onStop]: cancels any in-flight GPS request when the app enters background.
 *
 * Uses a [WeakReference] to [LocationServicesImpl] so that [ProcessLifecycleOwner]
 * (which lives for the entire process) does not prevent SDK cleanup. When the SDK
 * is reset via [CustomerIO.clearInstance], the services become eligible for GC,
 * the weak reference clears, and this observer becomes a no-op.
 *
 * Thread safety: all lifecycle callbacks are delivered on the main thread
 * by [ProcessLifecycleOwner], so no synchronization is needed.
 */
internal class LocationLifecycleObserver(
    locationServices: LocationServicesImpl,
    private val trackingMode: LocationTrackingMode
) : DefaultLifecycleObserver {

    private val servicesRef = WeakReference(locationServices)
    private var hasRequestedOnStart = false

    override fun onStart(owner: LifecycleOwner) {
        val services = servicesRef.get() ?: return
        if (trackingMode == LocationTrackingMode.ON_APP_START && !hasRequestedOnStart) {
            hasRequestedOnStart = true
            services.requestLocationUpdate()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val services = servicesRef.get() ?: return
        val wasCancelled = services.cancelInFlightRequest()
        // If the GPS request was still in flight when we backgrounded,
        // allow onStart to retry on the next foreground entry.
        if (wasCancelled) {
            hasRequestedOnStart = false
        }
    }
}
