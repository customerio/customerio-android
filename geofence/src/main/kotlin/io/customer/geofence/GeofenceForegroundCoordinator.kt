package io.customer.geofence

import io.customer.geofence.store.GeofenceRegionStore
import io.customer.location.LocationCoordinates
import io.customer.location.LocationServices
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.CancellationException

/**
 * What the SDK does when the app comes to the foreground.
 *
 * This used to live inside `ModuleGeofence`: the gates in `refreshOnForeground`, the
 * `retrySyncAwaitingLocation` fallback, and the lambda that sequences them, all built inline in
 * `scheduleForegroundWork`. `GeofenceLifecycleObserver` is genuinely humble — it flushes, reports
 * the permission tier, and calls a lambda — so everything worth testing was in a closure nothing
 * could construct without `ProcessLifecycleOwner`, `ModuleLocation` and a main-thread poster.
 *
 * The replay harness therefore had to reimplement the gates, and got them wrong: it dropped the
 * `locationMode` check and the entire retry fallback, so a `MANUAL`-mode drive replayed as
 * `AUTOMATIC` and a foreground landing while awaiting a fix skipped a whole sync pass. Duplicating
 * SDK decisions in a test is the failure this refactor exists to remove, not to relocate.
 *
 * Nothing here touches Android. [LocationServices] is already an interface, so the one outward call
 * — asking for a fix — is substitutable on its own.
 */
internal class GeofenceForegroundCoordinator(
    private val services: GeofenceServices,
    private val secureUserStore: SecureUserStore,
    private val locationServices: LocationServices,
    private val regionStore: GeofenceRegionStore,
    /**
     * The location module's cache. A lambda rather than the module itself: `ModuleLocation` is a
     * module-registry lookup, and taking it here would drag the whole registry into every test.
     */
    private val lastKnownLocation: () -> LocationCoordinates?,
    private val locationMode: GeofenceLocationMode,
    private val logger: GeofenceLogger
) {

    /**
     * One foreground entry.
     *
     * Suspending, and the caller owns the scope: the identity read below is a Keystore decrypt that
     * can block for hundreds of milliseconds on some OEMs, and lifecycle callbacks arrive on the
     * main thread.
     */
    suspend fun onForeground() {
        if (!takeFixForRefresh()) {
            retrySyncAwaitingLocation()
        }
    }

    /**
     * Takes a fresh fix on foreground entry so a refresh runs from where the device actually is.
     *
     * Returns false when no fix was taken: MANUAL leaves fixes to the host, nobody is identified to
     * sync for, or a sync is already stuck without one and [retrySyncAwaitingLocation] owns that
     * case.
     */
    fun takeFixForRefresh(): Boolean {
        if (locationMode != GeofenceLocationMode.AUTOMATIC) return false
        if (services.isAwaitingLocation()) return false
        // The sync drops a fix that arrives with nobody identified, so taking one before the first
        // identify — or on every resume after sign-out — spends location and battery on nothing.
        if (secureUserStore.getUserId().isNullOrEmpty()) return false
        // Arm before requesting, so the returning fix drives the sync.
        services.onRefreshRequested()
        locationServices.requestLocationUpdateSilently()
        return true
    }

    /**
     * A silent fix that never arrives — cancelled when the app backgrounds mid-fetch, timed out,
     * location services off — leaves the sync armed with nothing to consume it, and nothing else
     * re-requests one until the next cold launch. Foreground entry is the next chance.
     */
    private fun retrySyncAwaitingLocation() {
        // Cheap atomic read, so the healthy case never reaches the anchor read below.
        if (!services.isAwaitingLocation()) return
        try {
            if (services.isHostRefreshPending()) {
                // The host asked for a live fix — an anchor can't satisfy it, so don't sync from
                // one. Re-request like refreshFromCurrentLocation does (mode-independent); the
                // arriving fix consumes the flag and drives the sync.
                locationServices.requestLocationUpdateSilently()
                return
            }
            val anchor = anchor()
            // Re-check after the anchor read: a fix that landed meanwhile has already consumed the
            // flags and synced — retrying now would re-center on the pre-fix anchor.
            if (!services.isAwaitingLocation()) return
            services.onForegroundRetry(latitude = anchor?.latitude, longitude = anchor?.longitude)
            autoAcquireIfNeeded(anchor)
        } catch (e: CancellationException) {
            // Structured concurrency: swallowing this turns a cancelled foreground scope into a
            // silently-completed retry. Unreachable while this body has no suspension point, and
            // restored anyway — the convention is what stops the next edit introducing one.
            throw e
        } catch (e: Throwable) {
            logger.logSyncFailed("foreground retry failed: ${e.message}")
        }
    }

    /**
     * In [GeofenceLocationMode.AUTOMATIC], acquires a silent (no-analytics) fix when none is
     * available; the returning fix drives the sync via [GeofenceServices.onLocationAcquired].
     * MANUAL leaves it to the host.
     */
    fun autoAcquireIfNeeded(currentLocation: LocationCoordinates?) {
        if (currentLocation != null) return
        if (locationMode != GeofenceLocationMode.AUTOMATIC) return
        // No-ops without location permission.
        locationServices.requestLocationUpdateSilently()
    }

    /**
     * Where an identify / app-launch refresh should be anchored. Public because the identify and
     * launch paths need the same answer the foreground retry does, and computing it twice in two
     * places is how the two drifted apart before.
     */
    fun anchor(): LocationCoordinates? = resolveAnchor(
        registrationCenter = regionStore.getLastMovementTriggerLocation(),
        lastKnown = lastKnownLocation()
    )

    companion object {
        /**
         * Location to anchor an identify/launch refresh at: the last registration center (walked by
         * background movement EXITs) if set, else the location cache. Movement never updates the
         * cache, so on relaunch it can be stale — ranking a refresh from it after the device moved
         * while the app was dead would clobber the good registration with a set ranked around a
         * stale position.
         */
        fun resolveAnchor(
            registrationCenter: GeofenceLocation?,
            lastKnown: LocationCoordinates?
        ): LocationCoordinates? = registrationCenter
            ?.let { LocationCoordinates(latitude = it.latitude, longitude = it.longitude) }
            ?: lastKnown
    }
}
