package io.customer.geofence

import io.customer.geofence.store.GeofenceRegionStore
import io.customer.location.LocationCoordinates
import io.customer.location.LocationServices
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** What the SDK does when the app comes to the foreground, with no Android in it. */
internal class GeofenceForegroundCoordinator(
    private val services: GeofenceServices,
    private val secureUserStore: SecureUserStore,
    private val locationServices: LocationServices,
    private val regionStore: GeofenceRegionStore,
    /** The location module's cache, as a lambda so tests need not build the module registry. */
    private val lastKnownLocation: () -> LocationCoordinates?,
    private val locationMode: GeofenceLocationMode,
    private val logger: GeofenceLogger,
    private val dispatchers: DispatchersProvider
) {

    /**
     * Suspending, and the caller owns the scope: the identity read is a Keystore decrypt that can
     * block for hundreds of milliseconds, and lifecycle callbacks arrive on the main thread.
     *
     * The hop is enforced here rather than left to the caller. Without it this function had no
     * suspension point at all, so it ran wherever it was called from and the guarantee above was
     * documentation only — and a main-thread Keystore decrypt is an ANR that no CI job can catch.
     */
    suspend fun onForeground() = withContext(dispatchers.background) {
        // Guarded separately from the retry below, which is the point: the self-heal needs no
        // identity, and it used to be skipped entirely when the identity read threw on its way to
        // deciding whether to take a fix.
        val tookFix = try {
            takeFixForRefresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.logSyncFailed("foreground fix request failed: ${e.message}")
            false
        }
        if (!tookFix) {
            retrySyncAwaitingLocation()
        }
    }

    /** Takes a fresh fix so the refresh runs from where the device is. False when none was taken. */
    fun takeFixForRefresh(): Boolean {
        if (locationMode != GeofenceLocationMode.AUTOMATIC) return false
        if (services.isAwaitingLocation()) return false
        // The sync drops a fix that arrives with nobody identified, so do not spend one.
        if (secureUserStore.getUserId().isNullOrEmpty()) return false
        // Arm before requesting, so the returning fix drives the sync.
        services.onRefreshRequested()
        locationServices.requestLocationUpdateSilently()
        return true
    }

    /**
     * A silent fix that never arrived leaves the sync armed with nothing to consume it, and nothing
     * re-requests one until the next cold launch. Foreground entry is the next chance.
     */
    private fun retrySyncAwaitingLocation() {
        if (!services.isAwaitingLocation()) return
        try {
            if (services.isHostRefreshPending()) {
                // A host asked for a live fix; an anchor cannot satisfy it, so re-request instead of syncing.
                locationServices.requestLocationUpdateSilently()
                return
            }
            val anchor = anchor()
            // Re-check BOTH after the anchor read: a fix that landed meanwhile has already synced,
            // and a host request arriving meanwhile still cannot be satisfied by an anchor.
            if (!services.isAwaitingLocation()) return
            if (services.isHostRefreshPending()) {
                locationServices.requestLocationUpdateSilently()
                return
            }
            services.onForegroundRetry(latitude = anchor?.latitude, longitude = anchor?.longitude)
            autoAcquireIfNeeded(anchor)
        } catch (e: CancellationException) {
            // Never swallow cancellation.
            throw e
        } catch (e: Throwable) {
            logger.logSyncFailed("foreground retry failed: ${e.message}")
        }
    }

    /** In AUTOMATIC, acquires a silent fix when none is available; MANUAL leaves it to the host. */
    fun autoAcquireIfNeeded(currentLocation: LocationCoordinates?) {
        if (currentLocation != null) return
        if (locationMode != GeofenceLocationMode.AUTOMATIC) return
        locationServices.requestLocationUpdateSilently()
    }

    /** The anchor for an identify/launch refresh, shared with the foreground retry. */
    fun anchor(): LocationCoordinates? = resolveAnchor(
        registrationCenter = regionStore.getLastMovementTriggerLocation(),
        lastKnown = lastKnownLocation()
    )

    companion object {
        /**
         * Last registration center over the location cache: movement EXITs walk the center but
         * never update the cache, so after a relaunch the cache is the stale one.
         */
        fun resolveAnchor(
            registrationCenter: GeofenceLocation?,
            lastKnown: LocationCoordinates?
        ): LocationCoordinates? = registrationCenter
            ?.let { LocationCoordinates(latitude = it.latitude, longitude = it.longitude) }
            ?: lastKnown
    }
}
