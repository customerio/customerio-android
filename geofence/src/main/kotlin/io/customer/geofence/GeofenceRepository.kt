package io.customer.geofence

import android.Manifest
import androidx.annotation.RequiresPermission
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.toDomainConfig
import io.customer.geofence.api.toDomainRegions
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.getCachedConfigOrFallback
import io.customer.location.LocationCoordinates
import io.customer.sdk.communication.Event
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
     * On a genuine sign-out: drops OS-registered geofences and wipes user-scoped store state
     * (including cooldown history); workspace cache (regions, config) is preserved.
     *
     * No-op while a user is signed in — geofences are workspace-scoped, so the active user reuses
     * the registration and identify-sync reconciles it; tearing it down would only race that sync.
     */
    suspend fun reset(): Result<Unit>
}

internal class GeofenceRepositoryImpl(
    private val apiService: GeofenceApiService,
    private val store: GeofenceRegionStore,
    private val distanceFilter: GeofenceDistanceFilter,
    private val manager: GeofenceManager,
    private val secureUserStore: SecureUserStore,
    private val cooldownFilter: GeofenceCooldownFilter,
    private val transitionEmitter: GeofenceTransitionEmitter,
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

            val config = store.getCachedConfigOrFallback()
            // Decided under stateMutex so a concurrent sign-out reset can't wipe state right
            // after this reads pre-wipe freshness/registrations and SKIPs — that would leave
            // a just-identified user unmonitored. Pref reads only; network stays outside the lock.
            val action = stateMutex.withLock { refreshAction(LocationCoordinates(latitude, longitude), config) }
            // A launch/identify refresh runs with the persisted anchor, which a reboot can leave
            // pointing at a pre-reboot position — containment can't be trusted, so no synthesis
            // this pass (mirrors restoreFromCache). The flag drops once registration re-stamps
            // uptime. Sign-in is unaffected: sign-out clears the stamp with the anchor.
            val emitInitialEnter = !osStateWipedByReboot()
            return when (action) {
                RefreshAction.REMOTE -> performRemoteRefresh(userId, latitude, longitude, emitInitialEnter)
                RefreshAction.LOCAL -> performLocalRefresh(userId, latitude, longitude, config, emitInitialEnter = emitInitialEnter)
                RefreshAction.SKIP -> {
                    logger.logSyncSkippedFresh()
                    Result.success(Unit)
                }
            }
        } finally {
            refreshInProgress.set(false)
        }
    }

    // Decision table for identify/launch refresh.
    private fun refreshAction(location: LocationCoordinates, config: GeofenceConfig): RefreshAction {
        // Each distance is measured from its own reference: re-fetch from the last API fetch, re-rank
        // from the last registration (the movement-trigger center). Null (never set) → 0 → within radius.
        val distanceFromLastFetch = store.getLastApiFetchLocation()
            ?.distanceTo(location.latitude, location.longitude) ?: 0f
        val distanceFromLastRegistration = store.getLastMovementTriggerLocation()
            ?.distanceTo(location.latitude, location.longitude) ?: 0f

        return when {
            isStaleInTime(config) -> RefreshAction.REMOTE
            movedBeyondFetchRadius(distanceFromLastFetch, config) -> RefreshAction.REMOTE
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

    // The cached set only covers the area around the last fetch; once the device moves past the fetch
    // radius the set is no longer "nearby", so re-fetch from the server.
    private fun movedBeyondFetchRadius(distanceFromAnchor: Float, config: GeofenceConfig): Boolean =
        distanceFromAnchor >= config.remoteFetchRefreshTriggerRadius

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
            val config = store.getCachedConfigOrFallback()
            val distanceFromAnchor = anchor?.distanceTo(latitude, longitude) ?: 0f
            // No anchor yet (first EXIT after install / clearAll / sign-out) bootstraps from the server.
            // Otherwise, a non-remote move always re-ranks locally — that's the floor for any EXIT.
            val needsRemoteFetch = anchor == null ||
                movedBeyondFetchRadius(distanceFromAnchor, config)
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
        val cachedConfig = store.getCachedConfigOrFallback()
        return performLocalRefresh(
            userId = userId,
            latitude = effectiveLocation.latitude,
            longitude = effectiveLocation.longitude,
            cachedConfig = cachedConfig,
            register = manager::replaceGeofencesForBootRestore,
            // No initial-enter on boot restore: the cached anchor may be stale if the device moved
            // while off, so containment can't be trusted.
            emitInitialEnter = false
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun performRemoteRefresh(
        userId: String,
        latitude: Double,
        longitude: Double,
        emitInitialEnter: Boolean = true
    ): Result<Unit> {
        // The device location lets the backend return the nearby set; the request carries no user
        // identity, so it isn't attributable to a user.
        val fetchLocation = GeofenceLocation(latitude, longitude)
        val fetchResult = apiService.fetchGeofences(fetchLocation)
        return fetchResult.fold(
            onSuccess = { response ->
                // An unusable response throws (see toDomainRegions) — fail the refresh and
                // keep current registrations; never let it escape the handler-less scope.
                val mapped = runCatching {
                    response.toDomainRegions() to response.toDomainConfig()
                }.getOrElse { e ->
                    logger.logSyncFailed("response mapping failed: ${e.message}")
                    return@fold Result.failure(e)
                }
                val (regions, parsedConfig) = mapped
                // Config preference: server-shipped > last cached > constants.
                val config = parsedConfig ?: store.getCachedConfigOrFallback()
                registerNearestAndPersist(
                    userId = userId,
                    latitude = latitude,
                    longitude = longitude,
                    regions = regions,
                    config = config,
                    emitInitialEnter = emitInitialEnter,
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
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff,
        emitInitialEnter: Boolean = true
    ): Result<Unit> = registerNearestAndPersist(
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        regions = store.getCachedRegions(),
        config = cachedConfig,
        register = register,
        emitInitialEnter = emitInitialEnter
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
            unchangedRegisteredIds(regions)
        }
        return manager.replaceGeofences(
            regions = regions,
            existingBusinessIds = existingBusinessIds
        )
    }

    /** Business IDs already registered whose GMS-relevant params match the cache — safe to skip re-adding. */
    private fun unchangedRegisteredIds(regions: List<GeofenceRegion>): Set<String> {
        val registeredBusinessIds = store.getRegisteredIds() - GeofenceConstants.MOVEMENT_TRIGGER_ID
        val cachedById = store.getCachedRegions().associateBy { it.id }
        return regions
            .filter { region ->
                val cached = cachedById[region.id]
                region.id in registeredBusinessIds && cached?.equalsForRegistration(region) == true
            }
            .map { it.id }
            .toSet()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun registerNearestAndPersist(
        userId: String,
        latitude: Double,
        longitude: Double,
        regions: List<GeofenceRegion>,
        config: GeofenceConfig,
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff,
        onRegistered: () -> Unit = {},
        emitInitialEnter: Boolean = true
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
            // Synthesis baseline, snapshotted before register/persist mutate the store. No reboot
            // override here (unlike the registration diff): callers disable synthesis outright
            // while the reboot flag is up — the anchor can predate the reboot.
            val unchangedRegistered = unchangedRegisteredIds(nearest)
            val registrationResult = register(regionsToRegister).also { result ->
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
            if (registrationResult.isSuccess && emitInitialEnter) {
                // Identity can change during the awaited GMS call, and reset doesn't clear pending
                // delivery rows — never queue a synthetic ENTER for a signed-out/switched user.
                if (secureUserStore.getUserId() == userId) {
                    emitInitialEnters(nearest, unchangedRegistered, userId, latitude, longitude)
                } else {
                    logger.logSyncSkipped("user changed during refresh — initial-enter synthesis skipped")
                }
            }
            registrationResult
        }
    }

    /**
     * Synthesizes an ENTER for each newly-registered fence the device is already inside — GMS's
     * `INITIAL_TRIGGER_ENTER` unreliably drops this for a region added around a stationary device.
     * "New" = not in [unchangedRegisteredIds]: brand-new fences and re-added param changes fire,
     * an unchanged re-register stays silent. Cooldown-deduped, so a real GMS ENTER and this one
     * collapse to one event.
     */
    private suspend fun emitInitialEnters(
        candidates: List<GeofenceRegion>,
        unchangedRegisteredIds: Set<String>,
        userId: String,
        latitude: Double,
        longitude: Double
    ) {
        val timestamp = clock.currentTimeSeconds()
        candidates.forEach { region ->
            val newlyRegistered = region.id !in unchangedRegisteredIds
            val monitorsEnter = GeofenceTransitionType.ENTER in region.transitionTypes
            // Compare against the full radius: GMS has no per-region monitored-radius cap.
            val inside = region.distanceTo(latitude, longitude) <= region.radius
            if (!newlyRegistered || !monitorsEnter || !inside) return@forEach
            logger.logInitialEnterInside(region.id)
            transitionEmitter.emit(
                geofenceId = region.id,
                transition = Event.GeofenceTransition.ENTER,
                userId = userId,
                timestampSeconds = timestamp,
                geofenceName = region.name,
                metadata = region.metadata,
                geosetIds = region.geosetIds
            )
        }
    }

    override suspend fun reset(): Result<Unit> = stateMutex.withLock {
        // clearIdentify clears the user store synchronously before ResetEvent, so a non-null user
        // here means this reset was superseded by a new sign-in — skip the wipe (see interface doc).
        val currentUserId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
        if (currentUserId != null) {
            logger.logSyncSkipped("reset superseded by signed-in user")
            return@withLock Result.success(Unit)
        }
        // Clear OS-side first. On failure, preserve store state so the next refresh's stale-cleanup
        // diff can retry removal (unremoved OS regs would otherwise orphan). Cached regions/config
        // are kept; the freshness timestamp is dropped so the next login re-fetches.
        manager.clearAll().also { result ->
            if (result.isSuccess) {
                store.clearUserScopedState()
            }
            // Wipe the departing user's cooldown history on any genuine sign-out, even if the
            // OS clear failed — keys are user-scoped, so this is data hygiene, not correctness.
            cooldownFilter.clearAll()
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
