package io.customer.location.geofence

import android.Manifest
import androidx.annotation.RequiresPermission
import io.customer.location.geofence.api.GeofenceApiService
import io.customer.location.geofence.api.toDomainConfig
import io.customer.location.geofence.api.toDomainRegions
import io.customer.location.geofence.store.GeofenceRegionStore
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Geofence sync pipeline. Two public entry points:
 *
 * - [refresh] — for identify / app-launch. Honors the 24h freshness threshold; a recent
 *   successful sync short-circuits the API call.
 * - [handleMovement] — for movement-trigger EXIT. Two-tier dispatch: re-rank cached regions
 *   when within `remoteFetchRefreshTriggerRadius` of the last API anchor, otherwise hit
 *   the API for fresh data.
 */
internal interface GeofenceRepository {
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    suspend fun refresh(latitude: Double, longitude: Double): Result<Unit>

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    suspend fun handleMovement(latitude: Double, longitude: Double): Result<Unit>

    /**
     * Re-registers the cached geofences with the OS after a device reboot
     * (which drops all OS-side registrations). Uses the cached anchor as the
     * effective "current location" since no real-time location is available
     * during boot. Skips silently when there's nothing to restore — no user,
     * no anchor, or no cached config.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    suspend fun restoreFromCache(): Result<Unit>

    /**
     * Clears all geofence state: persisted regions in the store and OS-side
     * registrations via the manager. Called on user sign-out so the next user
     * (or anonymous session) doesn't inherit the previous user's geofences.
     */
    suspend fun reset(): Result<Unit>
}

internal class GeofenceRepositoryImpl(
    private val apiService: GeofenceApiService,
    private val store: GeofenceRegionStore,
    private val distanceFilter: GeofenceDistanceFilter,
    private val manager: GeofenceManager,
    private val secureUserStore: SecureUserStore,
    private val logger: GeofenceLogger
) : GeofenceRepository {

    // Dedup gate shared by refresh() and handleMovement(). If either is already running,
    // a concurrent trigger drops fast so we don't burn redundant work. Released in
    // `finally` so a failure or cancellation doesn't permanently latch the gate.
    private val refreshInProgress = AtomicBoolean(false)

    // Serializes state-mutation against reset() (sign-out). Held only around the
    // write block — the long-running API call happens outside the lock.
    private val stateMutex = Mutex()

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    override suspend fun refresh(latitude: Double, longitude: Double): Result<Unit> {
        if (!refreshInProgress.compareAndSet(false, true)) {
            logger.logSyncSkipped("refresh already in progress")
            return Result.success(Unit)
        }
        try {
            val userId = secureUserStore.getUserId()
            if (userId.isNullOrBlank()) {
                logger.logSyncSkipped("no identified user")
                return Result.success(Unit)
            }
            val lastSync = store.getLastSyncTimestamp()
            if (lastSync != null && System.currentTimeMillis() - lastSync < GeofenceConstants.STALE_THRESHOLD_MS) {
                logger.logSyncSkippedFresh()
                return Result.success(Unit)
            }
            return performRemoteRefresh(userId, latitude, longitude)
        } finally {
            refreshInProgress.set(false)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    override suspend fun handleMovement(latitude: Double, longitude: Double): Result<Unit> {
        if (!refreshInProgress.compareAndSet(false, true)) {
            logger.logSyncSkipped("refresh already in progress")
            return Result.success(Unit)
        }
        try {
            val userId = secureUserStore.getUserId()
            if (userId.isNullOrBlank()) {
                logger.logSyncSkipped("no identified user")
                return Result.success(Unit)
            }
            val anchor = store.getLastApiFetchLocation()
            val cachedConfig = store.getCachedConfig()
            // Tier B (remote fetch) when:
            // - no anchor yet (first EXIT after install / clearAll / sign-out), OR
            // - we've moved beyond the API threshold from the last fetch.
            // Otherwise Tier A: re-rank the cached regions for the new location.
            val remoteFetchThreshold = cachedConfig?.remoteFetchRefreshTriggerRadius
                ?: GeofenceConstants.FALLBACK_REMOTE_FETCH_RADIUS_METERS
            val needsRemoteFetch = anchor == null ||
                cachedConfig == null ||
                anchor.distanceTo(latitude, longitude) >= remoteFetchThreshold
            return if (needsRemoteFetch) {
                performRemoteRefresh(userId, latitude, longitude)
            } else {
                performLocalRefresh(userId, latitude, longitude, cachedConfig)
            }
        } finally {
            refreshInProgress.set(false)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    override suspend fun restoreFromCache(): Result<Unit> {
        if (!refreshInProgress.compareAndSet(false, true)) {
            logger.logSyncSkipped("refresh already in progress")
            return Result.success(Unit)
        }
        try {
            val userId = secureUserStore.getUserId()
            if (userId.isNullOrBlank()) {
                logger.logSyncSkipped("no identified user")
                return Result.success(Unit)
            }
            val anchor = store.getLastApiFetchLocation()
            val cachedConfig = store.getCachedConfig()
            if (anchor == null || cachedConfig == null) {
                logger.logSyncSkipped("no cached state to restore")
                return Result.success(Unit)
            }
            // Re-register using the anchor as the effective location; subsequent
            // movement EXITs will retarget the trigger to the user's actual location.
            return performLocalRefresh(userId, anchor.latitude, anchor.longitude, cachedConfig)
        } finally {
            refreshInProgress.set(false)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    private suspend fun performRemoteRefresh(
        userId: String,
        latitude: Double,
        longitude: Double
    ): Result<Unit> = apiService.fetchGeofences(userId, latitude, longitude).fold(
        onSuccess = { response ->
            val regions = response.toDomainRegions()
            val config = response.toDomainConfig()
            registerNearestAndPersist(
                userId = userId,
                latitude = latitude,
                longitude = longitude,
                regions = regions,
                config = config,
                // Cache + anchor + timestamp only on remote fetch; Tier A reuses them.
                onRegistered = {
                    store.saveCachedRegions(regions)
                    store.saveCachedConfig(config)
                    store.saveLastApiFetchLocation(GeofenceLocation(latitude, longitude))
                    store.setLastSyncTimestamp(System.currentTimeMillis())
                }
            )
        },
        onFailure = { error ->
            logger.logSyncFailed(error.message)
            Result.failure(error)
        }
    )

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    private suspend fun performLocalRefresh(
        userId: String,
        latitude: Double,
        longitude: Double,
        cachedConfig: GeofenceConfig
    ): Result<Unit> = registerNearestAndPersist(
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        regions = store.getCachedRegions(),
        config = cachedConfig
    )

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    private suspend fun registerNearestAndPersist(
        userId: String,
        latitude: Double,
        longitude: Double,
        regions: List<GeofenceRegion>,
        config: GeofenceConfig,
        onRegistered: () -> Unit = {}
    ): Result<Unit> {
        // Pure mapping + filter — no shared state, kept outside the lock.
        val nearest = distanceFilter.nearest(regions, latitude, longitude, config.maxBusinessGeofences)
        // Zero business geofences => register nothing (no movement trigger either).
        // Customers without configured geofences pay zero runtime cost.
        val regionsToRegister = if (nearest.isEmpty()) {
            emptyList()
        } else {
            listOf(buildMovementTrigger(latitude, longitude, config.localRefreshTriggerRadius)) + nearest
        }
        return stateMutex.withLock {
            // Recheck userId — sign-out or user switch may have happened during
            // the (potential) API call. Without this we'd write the previous
            // user's geofences for a signed-out / different user.
            val currentUserId = secureUserStore.getUserId()
            if (currentUserId != userId) {
                logger.logSyncSkipped("user changed during refresh")
                return@withLock Result.success(Unit)
            }
            manager.addGeofences(regionsToRegister).also { result ->
                if (result.isSuccess) {
                    // Stale cleanup runs ONLY after add succeeds. If add fails we
                    // leave previous OS registrations intact — better to keep
                    // serving the last-known-good set than to wipe everything and
                    // be unable to recover until the next refresh.
                    val previouslyRegistered = store.getRegisteredIds()
                    val newlyRegistered = regionsToRegister.map { it.id }.toSet()
                    val staleIds = previouslyRegistered - newlyRegistered
                    val staleRemovalSucceeded = if (staleIds.isNotEmpty()) {
                        manager.removeGeofencesByIds(staleIds.toList()).isSuccess
                    } else {
                        true
                    }
                    val currentlyRegistered = if (staleRemovalSucceeded) {
                        newlyRegistered
                    } else {
                        newlyRegistered + staleIds
                    }
                    store.saveRegisteredIds(currentlyRegistered)
                    onRegistered()
                    logger.logSyncSucceeded(nearest.size)
                }
            }
        }
    }

    override suspend fun reset(): Result<Unit> = stateMutex.withLock {
        // Skip if a new user is signed in by the time this reset runs.
        // geofenceScope runs on Dispatchers.Default, which doesn't order coroutines —
        // a refresh queued after this reset may have already written the new user's
        // state. Wiping now would clobber it; the new user's refresh handles previous-
        // user cleanup via the stale-diff in registerNearestAndPersist.
        val currentUserId = secureUserStore.getUserId()
        if (!currentUserId.isNullOrBlank()) {
            logger.logSyncSkipped("reset superseded by signed-in user")
            return@withLock Result.success(Unit)
        }
        // Order matters: clear OS-side FIRST, then the persisted record. If
        // manager.clearAll fails (transient GMS error), preserving the store
        // lets the next refresh's stale-cleanup diff see the previous regions
        // and retry their removal — without it, OS state orphans with no
        // record to drive the cleanup.
        manager.clearAll().also { result ->
            if (result.isSuccess) {
                store.clearAll()
            }
        }
    }

    private fun buildMovementTrigger(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float
    ): GeofenceRegion = GeofenceRegion(
        id = GeofenceConstants.MOVEMENT_TRIGGER_ID,
        latitude = latitude,
        longitude = longitude,
        radius = radiusMeters,
        transitionTypes = listOf(GeofenceTransitionType.EXIT)
    )
}
