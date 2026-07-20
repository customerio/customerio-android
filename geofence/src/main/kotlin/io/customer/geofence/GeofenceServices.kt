package io.customer.geofence

import android.annotation.SuppressLint
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Trigger-source-aware facade over [GeofenceRepository]. Centralises the decision
 * of whether to launch a refresh (we need a location and the location permissions)
 * and owns the coroutine scope so non-suspending callers (the broadcast receiver,
 * module init) stay simple.
 */
internal interface GeofenceServices {
    /**
     * Movement-trigger EXIT routes to the tier-dispatch path: re-rank cached regions
     * when within the API anchor's threshold, otherwise fetch fresh.
     *
     * Returns the refresh [Job] (null when the sync was skipped) so the broadcast
     * receiver can hold its goAsync window open until the refresh lands — the
     * movement trigger usually fires with the app backgrounded, where the process
     * is fair game for the OS the moment the receiver finishes.
     */
    fun onMovementTriggerExit(latitude: Double?, longitude: Double?): Job?

    /** Honours the freshness threshold — repeated identify within the window is a no-op. */
    fun onUserIdentified(latitude: Double?, longitude: Double?)

    /**
     * Honours the freshness threshold. Defensive trigger at module init so a
     * previously-identified user gets a sync even when the host app doesn't call
     * identify on this particular launch; the threshold makes it a cheap no-op
     * when identify also fires shortly after.
     */
    fun onAppLaunch(latitude: Double?, longitude: Double?)

    /**
     * Clears all geofence state so a subsequent user doesn't inherit the previous user's geofences.
     * Fired from the [Event.ResetEvent] subscriber. Whether to actually wipe is decided in
     * [GeofenceRepository.reset], which compares the now-signed-out identity against the user that
     * owns the live registration.
     */
    fun onUserSignedOut()

    /**
     * Arms a host-initiated refresh so the next acquired fix drives a sync even without
     * a prior no-location skip. The fetch itself is kicked by
     * [ModuleGeofence.refreshFromCurrentLocation] — kept out of this broadcast-reachable
     * facade, which must not depend on the location module.
     */
    fun onRefreshRequested()

    /**
     * Re-attempts a refresh when a fresh GPS fix arrives after a prior sync was
     * skipped for not-yet-available location, or after a host-initiated
     * [onRefreshRequested]. On fresh install identify can race ahead of the first
     * fix; without this hook the SDK would self-heal only on sign-out / next cold
     * launch.
     */
    fun onLocationAcquired(latitude: Double, longitude: Double)
}

internal class GeofenceServicesImpl(
    private val repository: GeofenceRepository,
    private val secureUserStore: SecureUserStore,
    private val regionStore: GeofenceRegionStore,
    private val scope: CoroutineScope,
    private val logger: GeofenceLogger,
    private val permissionChecker: GeofencePermissionChecker
) : GeofenceServices {

    // Rearm flag: set when a sync skips for no-location, cleared on any
    // successful trigger. onLocationAcquired only fires when this is set,
    // so streamed location updates don't cause repeated refreshes.
    private val lastSkippedForNoLocation = AtomicBoolean(false)

    // Set by a host-initiated refresh; the next acquired fix drives a sync
    // regardless of the no-location rearm flag.
    private val explicitRefreshRequested = AtomicBoolean(false)

    override fun onMovementTriggerExit(latitude: Double?, longitude: Double?): Job? =
        triggerSync(
            reason = REASON_MOVEMENT_EXIT,
            latitude = latitude,
            longitude = longitude,
            action = repository::handleMovement
        )

    override fun onUserIdentified(latitude: Double?, longitude: Double?) {
        triggerSync(
            reason = REASON_USER_IDENTIFIED,
            latitude = latitude,
            longitude = longitude,
            action = repository::refresh
        )
    }

    override fun onAppLaunch(latitude: Double?, longitude: Double?) {
        triggerSync(
            reason = REASON_APP_LAUNCH,
            latitude = latitude,
            longitude = longitude,
            action = repository::refresh
        )
    }

    override fun onRefreshRequested() {
        explicitRefreshRequested.set(true)
    }

    override fun onLocationAcquired(latitude: Double, longitude: Double) {
        // Act on a host-initiated refresh or the rising edge of a no-location skip;
        // otherwise this becomes a per-update refresh storm on hosts that stream
        // locations. Clear both flags so a single fix is consumed once.
        val requested = explicitRefreshRequested.compareAndSet(true, false)
        val rearmed = lastSkippedForNoLocation.compareAndSet(true, false)
        if (!requested && !rearmed) return
        val userId = secureUserStore.getUserId()
        // No user yet: skip; a later identify re-triggers.
        if (userId.isNullOrEmpty()) return
        onUserIdentified(latitude, longitude)
    }

    override fun onUserSignedOut() {
        // Drop any pending refresh intent so a previous user's request can't drive a
        // sync for the next user — sign-out wipes user-scoped session state.
        explicitRefreshRequested.set(false)
        lastSkippedForNoLocation.set(false)
        // Clear the registration-center anchor synchronously: repository.reset() also
        // clears it but runs on `scope`, so an in-process re-login (ResetEvent then
        // UserChangedEvent) would read the previous user's center and rank the new
        // user's geofences around it. Clearing it now makes the next identify fall
        // back to a no-location skip and acquire a fresh fix instead.
        regionStore.clearLastMovementTriggerLocation()
        logger.logGeofenceStateResetOnSignOut()
        scope.launch {
            repository.reset()
        }
    }

    // Returns the launched sync Job, or null when the sync was skipped.
    private fun triggerSync(
        reason: String,
        latitude: Double?,
        longitude: Double?,
        action: suspend (Double, Double) -> Result<Unit>
    ): Job? {
        if (latitude == null || longitude == null) {
            lastSkippedForNoLocation.set(true)
            logger.logSyncSkippedNoLocation(reason)
            return null
        }
        if (!permissionChecker.hasRequiredLocationPermissions()) {
            logger.logSyncSkippedNoPermission(reason)
            return null
        }
        if (!permissionChecker.isBackgroundDeliveryAvailable()) {
            logger.logBackgroundDeliveryUnavailable(reason)
        }
        lastSkippedForNoLocation.set(false)
        logger.logSyncTriggered(reason)
        // Guarded by permissionChecker above; Android kills the process when
        // permissions are revoked, so no mid-flight revocation to handle.
        @SuppressLint("MissingPermission")
        val syncJob = scope.launch {
            action(latitude, longitude)
        }
        return syncJob
    }

    private companion object {
        const val REASON_MOVEMENT_EXIT = "movement-trigger-exit"
        const val REASON_USER_IDENTIFIED = "user-identified"
        const val REASON_APP_LAUNCH = "app-launch"
    }
}
