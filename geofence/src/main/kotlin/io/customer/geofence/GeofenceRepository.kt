package io.customer.geofence

import android.Manifest
import androidx.annotation.RequiresPermission
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.toDomainConfig
import io.customer.geofence.api.toDomainRegions
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.location.LocationCoordinates
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Geofence sync pipeline. Two public entry points:
 *
 * - [refresh] — for identify / app-launch. Reuses the cached set within the freshness window
 *   (re-registering locally or skipping); otherwise fetches fresh from the API.
 * - [handleMovement] — for movement-trigger EXIT. Re-ranks the cached regions for the new
 *   location.
 */
internal interface GeofenceRepository {
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refresh(latitude: Double, longitude: Double): Result<Unit>

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun handleMovement(latitude: Double, longitude: Double): Result<Unit>

    /**
     * Re-registers the cached geofences with the OS after a device reboot
     * (which drops all OS-side registrations). Uses the cached anchor as the
     * effective "current location" since no real-time location is available
     * during boot. Skips silently when there's nothing to restore — no user,
     * no anchor, or no cached config.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
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
    private val logger: GeofenceLogger,
    private val syncMode: GeofenceSyncMode
) : GeofenceRepository {

    // Dedup gate shared by refresh() and handleMovement(). If either is already running,
    // a concurrent trigger drops fast so we don't burn redundant work. Released in
    // `finally` so a failure or cancellation doesn't permanently latch the gate.
    private val refreshInProgress = AtomicBoolean(false)

    // Serializes state-mutation against reset() (sign-out). Held only around the
    // write block — the long-running API call happens outside the lock.
    private val stateMutex = Mutex()

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
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

            val config = store.getCachedConfig() ?: GeofenceConfig.fallback()
            return when (refreshAction(LocationCoordinates(latitude, longitude), config)) {
                RefreshAction.REMOTE -> performRemoteRefresh(userId, latitude, longitude)
                RefreshAction.LOCAL -> performLocalRefresh(userId, latitude, longitude, config)
                RefreshAction.SKIP -> {
                    logger.logSyncSkippedFresh()
                    Result.success(Unit)
                }
            }
        } finally {
            refreshInProgress.set(false)
        }
    }

    // Decision table for identify/launch refresh. Only the re-fetch question depends on the sync
    // mode; staleness in time, staleness of the ranking, and OS-registration gaps are mode-agnostic.
    private fun refreshAction(location: LocationCoordinates, config: GeofenceConfig): RefreshAction {
        // Each distance is measured from its own reference: re-fetch from the last API fetch, re-rank
        // from the last registration (the movement-trigger center). Null (never set) → 0 → within radius.
        val distanceFromLastFetch = store.getLastApiFetchLocation()
            ?.distanceTo(location.latitude, location.longitude) ?: 0f
        val distanceFromLastRegistration = store.getLastMovementTriggerLocation()
            ?.distanceTo(location.latitude, location.longitude) ?: 0f

        return when {
            isStaleInTime(config) -> RefreshAction.REMOTE
            syncMode.movementRequiresRemoteFetch(distanceFromLastFetch, config) -> RefreshAction.REMOTE
            isRankingStale(distanceFromLastRegistration, config) -> RefreshAction.LOCAL
            hasUnregisteredCache() -> RefreshAction.LOCAL
            // Without this a fresh-cache launch after a reboot would SKIP — registeredIds survive the
            // reboot but GMS doesn't, leaving nothing monitored. Re-rank locally to re-register.
            osStateWipedByReboot() -> RefreshAction.LOCAL
            else -> RefreshAction.SKIP
        }
    }

    // Cache aged out of its freshness window (or was never fetched).
    private fun isStaleInTime(config: GeofenceConfig): Boolean {
        val lastSync = store.getLastSyncTimestamp() ?: return true
        return clock.currentTimeMillis() - lastSync >= config.remoteFetchRefreshExpiry
    }

    // The device has left the trigger radius since the nearest-N was last ranked, so the registered
    // set no longer reflects the closest geofences — re-rank locally (no network). This is exactly the
    // condition the live movement trigger fires on; refresh() catches an EXIT missed while app was dead.
    private fun isRankingStale(distanceFromLastRegistration: Float, config: GeofenceConfig): Boolean =
        distanceFromLastRegistration >= config.localRefreshTriggerRadius

    /** Cache holds regions but none are registered with the OS (e.g. regs lost on sign-out) → re-register. */
    private fun hasUnregisteredCache(): Boolean =
        store.getCachedRegions().isNotEmpty() && store.getRegisteredIds().isEmpty()

    /**
     * Uptime regressed since the last registration → the device rebooted, which wipes GMS geofences
     * even though registeredIds survive. Covers a missed BOOT_COMPLETED (stopped state, OEM battery
     * managers, emulator). Read by both [refreshAction] (force re-register over SKIP) and
     * [registerWithBusinessDiff] (re-register all rather than trust registeredIds).
     */
    private fun osStateWipedByReboot(): Boolean =
        store.getLastRegistrationUptime()?.let { clock.elapsedRealtime() < it } ?: false

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
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
            val config = store.getCachedConfig() ?: GeofenceConfig.fallback()
            val distanceFromAnchor = anchor?.distanceTo(latitude, longitude) ?: 0f
            // No anchor yet (first EXIT after install / clearAll / sign-out) bootstraps from the server.
            // Otherwise, a non-remote move always re-ranks locally — that's the floor for any EXIT.
            val needsRemoteFetch = anchor == null ||
                syncMode.movementRequiresRemoteFetch(distanceFromAnchor, config)
            return if (needsRemoteFetch) {
                performRemoteRefresh(userId, latitude, longitude)
            } else {
                performLocalRefresh(userId, latitude, longitude, config)
            }
        } finally {
            refreshInProgress.set(false)
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun restoreFromCache(): Result<Unit> {
        // Bypasses the in-flight gate: after a reboot, app-launch refresh's
        // registerWithBusinessDiff would see persisted registeredIds matching
        // the incoming set and skip business as "unchanged" — but GMS was wiped
        // by the reboot. Boot-restore must still run via
        // replaceGeofencesForBootRestore (no diff); stateMutex serializes the
        // concurrent writes.
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
        return performLocalRefresh(
            userId = userId,
            latitude = effectiveLocation.latitude,
            longitude = effectiveLocation.longitude,
            cachedConfig = cachedConfig,
            register = manager::replaceGeofencesForBootRestore
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun performRemoteRefresh(
        userId: String,
        latitude: Double,
        longitude: Double
    ): Result<Unit> {
        // No location is sent to fetch. The precise lat/lng below drive on-device ranking and the
        // anchor only — they never leave here.
        val fetchResult = apiService.fetchGeofences()
        return fetchResult.fold(
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
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
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
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun registerWithBusinessDiff(regions: List<GeofenceRegion>): Result<Unit> {
        // After a reboot GMS is empty, so re-register everything rather than trust the surviving
        // registeredIds (which would otherwise be skipped as "unchanged").
        val existingBusinessIds = if (osStateWipedByReboot()) {
            emptySet()
        } else {
            val registeredBusinessIds = store.getRegisteredIds() - GeofenceConstants.MOVEMENT_TRIGGER_ID
            val cachedById = store.getCachedRegions().associateBy { it.id }
            regions
                .filter { it.id in registeredBusinessIds && cachedById[it.id] == it }
                .map { it.id }
                .toSet()
        }
        return manager.replaceGeofences(
            regions = regions,
            existingBusinessIds = existingBusinessIds
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
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
        val nearest = distanceFilter.nearest(
            regions = regions,
            latitude = latitude,
            longitude = longitude,
            max = config.maxBusinessGeofences,
            maxDistanceMeters = config.maxMonitoringDistance
        )
        // Keep the movement trigger registered whenever the user has registerable geofences — even
        // if none are near enough now (all beyond maxMonitoringDistance) — so an EXIT re-ranks them
        // in as the device approaches. A truly empty set (no geofences / kill switch) registers nothing.
        val hasRegisterableGeofences = regions.isNotEmpty() && config.maxBusinessGeofences > 0
        val regionsToRegister = if (!hasRegisterableGeofences) {
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
                    // Stamp device uptime so the next refresh can detect a reboot (which wipes OS
                    // geofences) and force a full re-register instead of trusting registeredIds.
                    store.setLastRegistrationUptime(clock.elapsedRealtime())
                    // Track the user's location at each successful registration so boot restore can
                    // re-center close to their real position. Clear only when nothing is registered
                    // (no geofences / kill switch) — the trigger, and thus its location, is gone.
                    if (hasRegisterableGeofences) {
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
        // Clear OS-side FIRST, then wipe user-scoped store state. On
        // manager.clearAll failure preserve everything so the next refresh's
        // stale-cleanup diff retries removal — without it, unremoved OS regs
        // orphan with no record to drive cleanup. Cached regions/config are
        // kept; the freshness timestamp is dropped (in clearUserScopedState)
        // so the next login re-fetches.
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
