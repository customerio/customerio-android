package io.customer.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch

/**
 * Restores the persisted location, then handles identified users one at a time, in order, on
 * [scope] since both decrypt with the Keystore. One at a time so two identifies close together
 * can't both start a location request.
 */
internal class LocationUserChangeHandler(
    scope: CoroutineScope,
    private val config: LocationModuleConfig,
    private val locationTracker: LocationTracker,
    private val locationServices: LocationServices
) {
    private val identifiedUsers = Channel<Unit>(Channel.UNLIMITED)

    init {
        val restore = locationTracker.launchRestore(scope)
        scope.launch {
            // The ON_APP_START check below must see the restored location.
            restore.join()
            identifiedUsers.consumeEach { handleUserIdentified() }
        }
    }

    fun onUserIdentified() {
        identifiedUsers.trySend(Unit)
    }

    private fun handleUserIdentified() {
        locationTracker.onUserIdentified()

        // ON_APP_START's lifecycle one-shot fires per process, but resetContext()
        // wipes trackedLocation on logout — a subsequent identify in the same process
        // needs a fresh fetch. MANUAL deliberately doesn't auto-fetch.
        if (locationTracker.trackedLocation == null &&
            config.trackingMode == LocationTrackingMode.ON_APP_START
        ) {
            locationServices.requestLocationUpdate()
        }
    }
}
