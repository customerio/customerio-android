package io.customer.location

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Manages location lifecycle tied to app foreground/background transitions.
 *
 * Registered with [ProcessLifecycleOwner] during module initialization.
 * - [onStart]: hands off any pending geofence transitions to the analytics
 *   pipeline via [onForeground] (exactly-once against the WorkManager worker),
 *   then triggers a one-shot location request on the first foreground entry
 *   when [trackingMode] is [LocationTrackingMode.ON_APP_START].
 * - [onStop]: cancels any in-flight GPS request when the app enters background.
 *
 * Thread safety: all lifecycle callbacks are delivered on the main thread
 * by [ProcessLifecycleOwner], so no synchronization is needed.
 */
internal class LocationLifecycleObserver(
    private val locationServices: LocationServicesImpl,
    private val trackingMode: LocationTrackingMode,
    private val onForeground: () -> Unit = {}
) : DefaultLifecycleObserver {

    private var hasRequestedOnStart = false

    override fun onStart(owner: LifecycleOwner) {
        // Runs on every foreground entry, independent of trackingMode — geofence
        // deliveries must flush even in MANUAL mode.
        onForeground()
        if (trackingMode == LocationTrackingMode.ON_APP_START && !hasRequestedOnStart) {
            hasRequestedOnStart = true
            locationServices.requestLocationUpdate()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val wasCancelled = locationServices.cancelInFlightRequest()
        // If the GPS request was still in flight when we backgrounded,
        // allow onStart to retry on the next foreground entry.
        if (wasCancelled) {
            hasRequestedOnStart = false
        }
    }
}
