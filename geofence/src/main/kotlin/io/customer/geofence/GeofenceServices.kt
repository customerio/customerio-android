package io.customer.geofence

import android.annotation.SuppressLint
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
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
     */
    fun onMovementTriggerExit(latitude: Double?, longitude: Double?)

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
     * Clears all geofence state so a subsequent user doesn't inherit the previous
     * user's geofences. Fired when [Event.UserChangedEvent] arrives with a null userId.
     */
    fun onUserSignedOut()

    /**
     * Re-attempts a refresh when a fresh GPS fix arrives after a prior sync was
     * skipped for not-yet-available location. On fresh install identify can race
     * ahead of the first fix; without this hook the SDK would self-heal only on
     * sign-out / next cold launch.
     */
    fun onLocationAcquired(latitude: Double, longitude: Double)
}

internal class GeofenceServicesImpl(
    private val repository: GeofenceRepository,
    private val secureUserStore: SecureUserStore,
    private val scope: CoroutineScope,
    private val logger: GeofenceLogger,
    private val permissionChecker: GeofencePermissionChecker
) : GeofenceServices {

    // Rearm flag: set when a sync skips for no-location, cleared on any
    // successful trigger. onLocationAcquired only fires when this is set,
    // so streamed location updates don't cause repeated refreshes.
    private val lastSkippedForNoLocation = AtomicBoolean(false)

    override fun onMovementTriggerExit(latitude: Double?, longitude: Double?) {
        triggerSync(
            reason = REASON_MOVEMENT_EXIT,
            latitude = latitude,
            longitude = longitude,
            action = repository::handleMovement
        )
    }

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

    override fun onLocationAcquired(latitude: Double, longitude: Double) {
        // Only act on the rising edge of a no-location skip; otherwise this
        // becomes a per-update refresh storm on hosts that stream locations.
        if (!lastSkippedForNoLocation.compareAndSet(true, false)) return
        val userId = secureUserStore.getUserId()
        // No user: a future identify catches the now-cached location.
        if (userId.isNullOrEmpty()) return
        onUserIdentified(latitude, longitude)
    }

    override fun onUserSignedOut() {
        // Snapshot the userId synchronously — the datapipelines `ResetEvent`
        // subscriber races to clear `secureUserStore`, so a deferred read
        // inside `reset()` could see null and misclassify a normal sign-out
        // as a re-login.
        val signedOutUserId = secureUserStore.getUserId()
        logger.logGeofenceStateResetOnSignOut()
        scope.launch {
            repository.reset(signedOutUserId)
        }
    }

    private fun triggerSync(
        reason: String,
        latitude: Double?,
        longitude: Double?,
        action: suspend (Double, Double) -> Result<Unit>
    ) {
        if (latitude == null || longitude == null) {
            lastSkippedForNoLocation.set(true)
            logger.logSyncSkippedNoLocation(reason)
            return
        }
        if (!permissionChecker.hasRequiredLocationPermissions()) {
            logger.logSyncSkippedNoPermission(reason)
            return
        }
        if (!permissionChecker.isBackgroundDeliveryAvailable()) {
            logger.logBackgroundDeliveryUnavailable(reason)
        }
        lastSkippedForNoLocation.set(false)
        logger.logSyncTriggered(reason)
        // Guarded by permissionChecker above; Android kills the process when
        // permissions are revoked, so no mid-flight revocation to handle.
        @SuppressLint("MissingPermission")
        scope.launch {
            action(latitude, longitude)
        }
    }

    private companion object {
        const val REASON_MOVEMENT_EXIT = "movement-trigger-exit"
        const val REASON_USER_IDENTIFIED = "user-identified"
        const val REASON_APP_LAUNCH = "app-launch"
    }
}
