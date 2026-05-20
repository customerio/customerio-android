package io.customer.location.geofence

import android.Manifest
import androidx.annotation.RequiresPermission
import io.customer.location.geofence.api.GeofenceApiService
import io.customer.location.geofence.api.toDomainConfig
import io.customer.location.geofence.api.toDomainRegions
import io.customer.location.geofence.store.GeofenceRegionStore
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Geofence sync pipeline. Two public entry points:
 *
 * - [refresh] — for identify / app-launch. A recent successful sync within the
 *   freshness window short-circuits the API call.
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
     * Drops OS-registered geofences + wipes user-specific store state on
     * sign-out. Workspace cache (regions, config, last-sync) is preserved.
     *
     * [signedOutUserId] is the user being signed out, captured synchronously
     * at the call site. Used to distinguish a normal sign-out (current user
     * still matches because `secureUserStore` hasn't been cleared yet by its
     * own ResetEvent subscriber) from a true re-login (different user signed
     * in during the reset window, skip wipe).
     */
    suspend fun reset(signedOutUserId: String?): Result<Unit>
}

internal class GeofenceRepositoryImpl(
    private val apiService: GeofenceApiService,
    private val store: GeofenceRegionStore,
    private val distanceFilter: GeofenceDistanceFilter,
    private val manager: GeofenceManager,
    private val secureUserStore: SecureUserStore,
    private val clock: Clock,
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
            if (lastSync != null) {
                // Freshness window comes from the cached server config; falls back
                // to STALE_THRESHOLD_MS when no cache exists or the value is
                // non-positive (defensive against bad config).
                val threshold = store.getCachedConfig()?.remoteFetchRefreshExpiry
                    ?.takeIf { it > 0 }
                    ?: GeofenceConstants.STALE_THRESHOLD_MS
                if (clock.currentTimeMillis() - lastSync < threshold) {
                    // Cache fresh — if OS regs were wiped on sign-out, re-
                    // register from cache instead of skipping; otherwise the
                    // new user has no geofences until stale-window expiry.
                    val cachedRegions = store.getCachedRegions()
                    val registered = store.getRegisteredIds()
                    if (cachedRegions.isNotEmpty() && registered.isEmpty()) {
                        val cachedConfig = store.getCachedConfig() ?: GeofenceConfig.fallback()
                        return performLocalRefresh(userId, latitude, longitude, cachedConfig)
                    }
                    logger.logSyncSkippedFresh()
                    return Result.success(Unit)
                }
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
            val cachedConfig = store.getCachedConfig() ?: GeofenceConfig.fallback()
            // Tier B (remote fetch) when:
            // - no anchor yet (first EXIT after install / clearAll / sign-out), OR
            // - we've moved beyond the API threshold from the last fetch.
            // Otherwise Tier A: re-rank the cached regions for the new location.
            val needsRemoteFetch = anchor == null ||
                anchor.distanceTo(latitude, longitude) >= cachedConfig.remoteFetchRefreshTriggerRadius
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
            // Prefer the most recent movement-trigger center as the effective
            // location — it tracks Tier A drift and is much closer to the user's
            // real position than the anchor (only updated on Tier B fetches).
            // Fall back to the anchor if there's no movement-trigger location yet
            // (older cache / first-ever boot restore).
            val effectiveLocation = store.getLastMovementTriggerLocation()
                ?: store.getLastApiFetchLocation()
            if (effectiveLocation == null) {
                logger.logSyncSkipped("no cached state to restore")
                return Result.success(Unit)
            }
            val cachedConfig = store.getCachedConfig() ?: GeofenceConfig.fallback()
            // Boot-restore variant — see [GeofenceManager.replaceGeofencesForBootRestore].
            return performLocalRefresh(
                userId = userId,
                latitude = effectiveLocation.latitude,
                longitude = effectiveLocation.longitude,
                cachedConfig = cachedConfig,
                register = manager::replaceGeofencesForBootRestore
            )
        } finally {
            refreshInProgress.set(false)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    private suspend fun performRemoteRefresh(
        userId: String,
        latitude: Double,
        longitude: Double
    ): Result<Unit> = apiService.fetchGeofences(latitude, longitude).fold(
        onSuccess = { response ->
            val regions = response.toDomainRegions()
            // Config preference: server-shipped > last cached > constants.
            val parsedConfig = response.toDomainConfig()
            val config = parsedConfig
                ?: store.getCachedConfig()
                ?: GeofenceConfig.fallback()
            registerNearestAndPersist(
                userId = userId,
                latitude = latitude,
                longitude = longitude,
                regions = regions,
                config = config,
                // Cache + anchor + timestamp only on remote fetch; Tier A reuses them.
                // Skip the config save when backend didn't ship one this response —
                // a null parse must not clobber a previously cached value.
                onRegistered = {
                    store.saveCachedRegions(regions)
                    parsedConfig?.let { store.saveCachedConfig(it) }
                    store.saveLastApiFetchLocation(GeofenceLocation(latitude, longitude))
                    store.setLastSyncTimestamp(clock.currentTimeMillis())
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
        cachedConfig: GeofenceConfig,
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff
    ): Result<Unit> = registerNearestAndPersist(
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        regions = store.getCachedRegions(),
        config = cachedConfig,
        register = register
    )

    /**
     * Default register path for Tier A / Tier B refreshes. An ID is treated
     * as skip-safe only when the cached region equals the incoming one — any
     * param drift forces a re-register so GMS doesn't keep stale values.
     * Boot restore bypasses this; OS state is empty after reboot.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    private suspend fun registerWithBusinessDiff(regions: List<GeofenceRegion>): Result<Unit> {
        val registeredBusinessIds = store.getRegisteredIds() - GeofenceConstants.MOVEMENT_TRIGGER_ID
        val cachedById = store.getCachedRegions().associateBy { it.id }
        val existingBusinessIds = regions
            .filter { it.id in registeredBusinessIds && cachedById[it.id] == it }
            .map { it.id }
            .toSet()
        return manager.replaceGeofences(
            regions = regions,
            existingBusinessIds = existingBusinessIds
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    private suspend fun registerNearestAndPersist(
        userId: String,
        latitude: Double,
        longitude: Double,
        regions: List<GeofenceRegion>,
        config: GeofenceConfig,
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff,
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
            register(regionsToRegister).also { result ->
                if (result.isSuccess) {
                    // Stale cleanup — Manager added new−existing, we remove
                    // existing−new. Runs only on add success; on failure leave
                    // previous registrations intact rather than wipe.
                    val existingIds = store.getRegisteredIds()
                    val newIds = regionsToRegister.map { it.id }.toSet()
                    val staleIds = existingIds - newIds
                    val staleRemovalSucceeded = if (staleIds.isNotEmpty()) {
                        manager.removeGeofencesByIds(staleIds.toList()).isSuccess
                    } else {
                        true
                    }
                    val idsToSave = if (staleRemovalSucceeded) {
                        newIds
                    } else {
                        newIds + staleIds
                    }
                    store.saveRegisteredIds(idsToSave)
                    // Track the user's location at each successful registration so
                    // boot restore can re-center close to their real position. Clear
                    // when the business set transitions to empty — no trigger exists.
                    if (nearest.isNotEmpty()) {
                        store.saveLastMovementTriggerLocation(GeofenceLocation(latitude, longitude))
                    } else {
                        store.clearLastMovementTriggerLocation()
                    }
                    onRegistered()
                    logger.logSyncSucceeded(nearest.size)
                }
            }
        }
    }

    override suspend fun reset(signedOutUserId: String?): Result<Unit> = stateMutex.withLock {
        // Skip only when a DIFFERENT user is signed in — see KDoc on [reset].
        val currentUserId = secureUserStore.getUserId()
        if (!currentUserId.isNullOrBlank() && currentUserId != signedOutUserId) {
            logger.logSyncSkipped("reset superseded by signed-in user")
            return@withLock Result.success(Unit)
        }
        // Clear OS-side FIRST, then wipe user-specific store state. On
        // manager.clearAll failure preserve everything so the next refresh's
        // stale-cleanup diff retries removal — without it, unremoved OS regs
        // orphan with no record to drive cleanup. Workspace cache (regions,
        // config, lastSync) is always preserved.
        manager.clearAll().also { result ->
            if (result.isSuccess) {
                store.clearUserScopedState()
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
