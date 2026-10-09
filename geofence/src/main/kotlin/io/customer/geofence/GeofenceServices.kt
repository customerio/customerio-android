package io.customer.geofence

import android.annotation.SuppressLint
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.location.LocationCoordinates
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal interface GeofenceServices {
    /**
     * Returns the [Job] so the receiver can hold goAsync open: the trigger usually fires
     * backgrounded, where the process may be killed as soon as the receiver finishes.
     *
     * @param fixQuality when the triggering fix was taken and how accurate it was, which decides
     * whether it can prove the device outside the fences this pass registers.
     */
    fun onMovementTriggerExit(
        latitude: Double?,
        longitude: Double?,
        movementTriggerRadius: suspend () -> Float? = { null },
        fixQuality: GeofenceFixQuality = GeofenceFixQuality.UNKNOWN
    ): Job?

    fun onUserIdentified(latitude: Double?, longitude: Double?)

    /**
     * Syncs a previously identified user even when the host skips identify this launch; the
     * freshness threshold makes it a no-op when identify follows.
     */
    fun onAppLaunch(latitude: Double?, longitude: Double?)

    fun onUserSignedOut()

    /**
     * Only arms; [ModuleGeofence.refreshFromCurrentLocation] requests the fix, since this
     * broadcast-reachable facade must not depend on the location module.
     */
    fun onRefreshRequested()

    fun onLocationAcquired(
        latitude: Double,
        longitude: Double,
        quality: GeofenceFixQuality = GeofenceFixQuality.UNKNOWN
    )

    /** Same handling as [onAppLaunch], separate so logs tell the two apart. */
    fun onForegroundRetry(latitude: Double?, longitude: Double?)

    /** Peeks only; [onLocationAcquired] owns clearing the flags. */
    fun isAwaitingLocation(): Boolean

    /**
     * Peeks only. A host refresh wants a live fix, so the foreground retry must re-request rather
     * than use the anchor, or the flag stays armed forever.
     */
    fun isHostRefreshPending(): Boolean
}

internal class GeofenceServicesImpl(
    private val repository: GeofenceRepository,
    private val secureUserStore: SecureUserStore,
    private val regionStore: GeofenceRegionStore,
    private val cooldownFilter: GeofenceCooldownFilter,
    private val polygonController: PolygonGeofenceServiceController,
    private val scope: CoroutineScope,
    private val logger: GeofenceLogger,
    private val permissionChecker: GeofencePermissionChecker,
    private val clock: Clock
) : GeofenceServices {

    private val lastSkippedForNoLocation = AtomicBoolean(false)

    private val explicitRefreshRequested = AtomicBoolean(false)

    override fun onMovementTriggerExit(
        latitude: Double?,
        longitude: Double?,
        movementTriggerRadius: suspend () -> Float?,
        fixQuality: GeofenceFixQuality
    ): Job? {
        // triggerSync checks permission before this runs.
        @SuppressLint("MissingPermission")
        val action: suspend (Double, Double) -> Result<Unit> = { lat, lng ->
            repository.handleMovement(
                latitude = lat,
                longitude = lng,
                movementTriggerRadius = movementTriggerRadius,
                fixQuality = fixQuality
            )
        }
        return triggerSync(
            reason = REASON_MOVEMENT_EXIT,
            latitude = latitude,
            longitude = longitude,
            action = action
        )
    }

    override fun onUserIdentified(latitude: Double?, longitude: Double?) {
        // Here, not on the first OS callback: beginUserSession clears the sync stamp, so the refresh
        // goes REMOTE and arms routing first. Else the first callback removes every live fence.
        secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }?.let(regionStore::beginUserSession)
        triggerSync(
            reason = REASON_USER_IDENTIFIED,
            latitude = latitude,
            longitude = longitude,
            action = repository::refresh
        )
    }

    override fun onAppLaunch(latitude: Double?, longitude: Double?) {
        // Never redefine a session: an identify can land meanwhile, and reopening the older user
        // would clear the routing that identify's refresh just armed.
        secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
            ?.let(regionStore::beginUserSessionIfAbsent)
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

    override fun onLocationAcquired(
        latitude: Double,
        longitude: Double,
        quality: GeofenceFixQuality
    ) {
        if (!isAwaitingLocation()) return
        // The only freshness gate; the repository trusts these fixes as live. A stale fix keeps the
        // intent armed, since spending it would leave nothing to seed containment.
        if (!quality.isFresh(clock.elapsedRealtime())) {
            logger.logSyncSkipped("fix too old to judge containment — live-fix intent kept")
            return
        }
        // Only a host refresh or the rising edge of a no-location skip, else hosts that stream
        // locations cause a refresh storm. Clearing both consumes a fix once.
        val requested = explicitRefreshRequested.compareAndSet(true, false)
        val rearmed = lastSkippedForNoLocation.compareAndSet(true, false)
        if (!requested && !rearmed) return
        val userId = secureUserStore.getUserId()
        // A later identify re-triggers.
        if (userId.isNullOrEmpty()) return
        // refreshFromLiveFix, not refresh: the flags above are already consumed, so a pass dropped on
        // a slot collision would waste this fix with nothing to request another.
        // triggerSync checks permission before this runs.
        @SuppressLint("MissingPermission")
        val action: suspend (Double, Double) -> Result<Unit> = { lat, lng ->
            repository.refreshFromLiveFix(lat, lng, fixQuality = quality)
        }
        triggerSync(
            reason = REASON_LOCATION_ACQUIRED,
            latitude = latitude,
            longitude = longitude,
            action = action
        )
    }

    override fun onForegroundRetry(latitude: Double?, longitude: Double?) {
        triggerSync(
            reason = REASON_FOREGROUND_RETRY,
            latitude = latitude,
            longitude = longitude,
            action = repository::refresh
        )
    }

    override fun isAwaitingLocation(): Boolean =
        lastSkippedForNoLocation.get() || explicitRefreshRequested.get()

    override fun isHostRefreshPending(): Boolean = explicitRefreshRequested.get()

    override fun onUserSignedOut() {
        // So a previous user's request can't drive a sync for the next user.
        explicitRefreshRequested.set(false)
        lastSkippedForNoLocation.set(false)
        cooldownFilter.clearAll()
        // Now, not in repository.reset(): its OS cleanup can be held up by a stalled GMS call or
        // the repository lock.
        polygonController.clearUserSessionRetainingOsRegistrations()
        // Synchronously, not only in repository.reset() on `scope`: an in-process re-login would
        // otherwise rank the new user's geofences around the previous user's center.
        regionStore.clearLastMovementTriggerLocation()
        logger.logGeofenceStateResetOnSignOut()
        scope.launch {
            runSafely("sign-out reset") { repository.reset() }
        }
    }

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
        // NaN, infinite and out-of-range coordinates make `Location.distanceBetween` throw mid-sync.
        if (!LocationCoordinates.isValid(latitude, longitude)) {
            lastSkippedForNoLocation.set(true)
            logger.logSyncSkippedInvalidLocation(reason, latitude, longitude)
            return null
        }
        if (!permissionChecker.hasRequiredLocationPermissions()) {
            // Without permission the OS cannot preserve a trustworthy monitoring interval. Keep
            // queued events, but discard live visit state so a later fix cannot invent duration.
            regionStore.clearDwellVisits()
            logger.logSyncSkippedNoPermission(reason)
            return null
        }
        if (!permissionChecker.isBackgroundDeliveryAvailable()) {
            logger.logBackgroundDeliveryUnavailable(reason)
        }
        lastSkippedForNoLocation.set(false)
        logger.logSyncTriggered(reason)
        // Checked above; revoking a permission kills the process, so none is revoked mid-flight.
        @SuppressLint("MissingPermission")
        val syncJob = scope.launch {
            runSafely("sync ($reason)") { action(latitude, longitude) }
        }
        return syncJob
    }

    /**
     * [scope] has no exception handler, so anything escaping would crash the host. [Error]s
     * propagate.
     */
    private suspend fun runSafely(description: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logSyncFailed("$description threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private companion object {
        const val REASON_MOVEMENT_EXIT = "movement-trigger-exit"
        const val REASON_USER_IDENTIFIED = "user-identified"
        const val REASON_APP_LAUNCH = "app-launch"
        const val REASON_FOREGROUND_RETRY = "foreground-retry"
        const val REASON_LOCATION_ACQUIRED = "location-acquired"
    }
}
