package io.customer.geofence

import android.Manifest
import androidx.annotation.RequiresPermission
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.toDomainConfig
import io.customer.geofence.api.toDomainRegions
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.getCachedConfigOrFallback
import io.customer.location.LocationCoordinates
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Geofence sync pipeline. Two public entry points:
 *
 * - [refresh] — for identify / app-launch. Reuses the cached set within the freshness window
 *   (re-registering locally or skipping); otherwise fetches fresh from the API. Waits behind an
 *   older pass so a rapid user switch cannot lose the new user's only refresh.
 * - [refreshFromLiveFix] — same serialized pass, for a fix the SDK asked for and received.
 * - [handleMovement] — for movement-trigger EXIT. Re-ranks the cached regions for the new
 *   location.
 */
internal interface GeofenceRepository {
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refresh(latitude: Double, longitude: Double): Result<Unit>

    /**
     * [refresh] for a just-acquired fix. This entry point names the stronger source semantics; both
     * paths serialize because dropping either can strand monitoring after an identity change.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refreshFromLiveFix(latitude: Double, longitude: Double): Result<Unit>

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
    private val packageInfo: GeofencePackageInfo,
    private val logger: GeofenceLogger,
    private val polygonController: PolygonGeofenceServiceController? = null
) : GeofenceRepository {

    // One sync pass at a time. Every caller waits for the slot: duplicate app-launch/identify work
    // is re-evaluated against the first pass's result, while a different user or live fix gets its
    // own pass.
    // Released in `finally` so a failure or cancellation can't latch the gate.
    private val refreshInProgress = AtomicBoolean(false)

    // Serializes state-mutation against reset() (sign-out). Held only around the
    // write block — the long-running API call happens outside the lock.
    private val stateMutex = Mutex()

    private fun tryTakeRefreshSlot(): Boolean = refreshInProgress.compareAndSet(false, true)

    private fun releaseRefreshSlot() {
        refreshInProgress.set(false)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun refresh(latitude: Double, longitude: Double): Result<Unit> {
        if (!awaitRefreshSlot()) {
            logger.logSyncSkipped("refresh already in progress after waiting")
            return Result.success(Unit)
        }
        return runRefresh(latitude, longitude)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun refreshFromLiveFix(latitude: Double, longitude: Double): Result<Unit> {
        if (!awaitRefreshSlot()) {
            logger.logSyncSkipped("refresh already in progress after waiting")
            return Result.success(Unit)
        }
        return runRefresh(latitude, longitude)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun runRefresh(latitude: Double, longitude: Double): Result<Unit> {
        try {
            val userId = secureUserStore.getUserId()
            if (userId.isNullOrBlank()) {
                logger.logSyncSkipped("no identified user")
                return Result.success(Unit)
            }

            val config = store.getCachedConfigOrFallback()
            // Captured with the coordinates: an exit claimed mid-sync must beat this fix's geometry.
            val containmentEpoch = store.containmentEpoch()
            // Decided under stateMutex so a concurrent sign-out reset can't wipe state right
            // after this reads pre-wipe freshness/registrations and SKIPs — that would leave
            // a just-identified user unmonitored. Pref reads only; network stays outside the lock.
            val action = stateMutex.withLock { refreshAction(LocationCoordinates(latitude, longitude), config) }
            // A launch/identify refresh runs with the persisted anchor, which a reboot or app update
            // can leave pointing at a stale position — containment can't be trusted, so no synthesis
            // this pass (mirrors restoreFromCache). Drops once registration re-stamps.
            val emitInitialEnter = !osStateWiped()
            return when (action) {
                RefreshAction.REMOTE -> performRemoteRefresh(userId, latitude, longitude, containmentEpoch, emitInitialEnter)
                RefreshAction.LOCAL -> performLocalRefresh(userId, latitude, longitude, config, containmentEpoch, emitInitialEnter = emitInitialEnter)
                RefreshAction.SKIP -> {
                    logger.logSyncSkippedFresh()
                    Result.success(Unit)
                }
            }
        } finally {
            releaseRefreshSlot()
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
            // Without this a fresh-cache launch after a reboot or app update would SKIP —
            // registeredIds survive but GMS state doesn't, leaving nothing monitored.
            osStateWiped() -> RefreshAction.LOCAL
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

    /** Cache holds regions but OS or current-session routing state is absent → re-register. */
    private fun hasUnregisteredCache(): Boolean =
        store.getCachedRegions().isNotEmpty() &&
            (store.getRegisteredIds().isEmpty() || store.getRoutableRegisteredIds().isEmpty())

    /**
     * Uptime regressed since the last registration → the device rebooted, which wipes GMS geofences
     * even though registeredIds survive. Covers a missed BOOT_COMPLETED (stopped state, OEM battery
     * managers, emulator). Read by both [refreshAction] (force re-register over SKIP) and
     * [registerWithBusinessDiff] (re-register all rather than trust registeredIds).
     */
    private fun osStateWipedByReboot(): Boolean =
        store.getLastRegistrationUptime()?.let { clock.elapsedRealtime() < it } ?: false

    /**
     * Package replaced since the last registration — an app update can cancel the geofence
     * PendingIntent, silently dropping OS registrations while registeredIds survive.
     * Same consequences and call sites as [osStateWipedByReboot].
     */
    private fun osStateWipedByAppUpdate(): Boolean {
        val current = packageInfo.lastUpdateTimeMs() ?: return false
        // No stamp but live registrations = they predate stamping (this upgrade is itself an
        // app update) — treat as wiped.
        val stamped = store.getLastRegistrationPackageUpdateTime()
            ?: return store.getRegisteredIds().isNotEmpty()
        return current != stamped
    }

    private fun osStateWiped(): Boolean = osStateWipedByReboot() || osStateWipedByAppUpdate()

    /**
     * Takes the in-flight slot for a pass that can't be dropped, waiting for the holder instead.
     * For a movement pass, the OS holds a fired trigger in the outside state, so returning without
     * re-centring it stops discovery until the next app open, reboot or update; for a live fix, the
     * fix is spent on arrival and nothing re-requests one.
     */
    private suspend fun awaitRefreshSlot(): Boolean {
        // Bounded polling rather than a timeout around the wait: a deadline enforced by cancellation
        // can land between a winning CAS and the block's completion, discarding the result while the
        // slot stays taken — latching the flag so every later pass drops for the life of the process.
        // Here the CAS result is the return value, so a slot can only be held by a caller that got
        // `true`. Scheduling delay stretches the wait rather than shortening it, which is the safe
        // direction: the point is to outlast the holder, not to give up on time.
        repeat(MOVEMENT_SLOT_ATTEMPTS) {
            if (tryTakeRefreshSlot()) return true
            delay(MOVEMENT_SLOT_POLL)
        }
        return tryTakeRefreshSlot()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun handleMovement(latitude: Double, longitude: Double): Result<Unit> {
        if (!awaitRefreshSlot()) {
            logger.logSyncSkipped("refresh already in progress after waiting")
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
            val containmentEpoch = store.containmentEpoch()
            val distanceFromAnchor = anchor?.distanceTo(latitude, longitude) ?: 0f
            // No anchor yet (first EXIT after install / clearAll / sign-out) bootstraps from the server.
            // Otherwise, a non-remote move always re-ranks locally — that's the floor for any EXIT.
            val needsRemoteFetch = anchor == null ||
                movedBeyondFetchRadius(distanceFromAnchor, config)
            // Initial-enter synthesis stays on here (unlike refresh): containment is judged against
            // the OS's live triggering fix, so a reboot/app-update wipe can't make it stale.
            return if (needsRemoteFetch) {
                val remote = performRemoteRefresh(userId, latitude, longitude, containmentEpoch)
                if (remote.isFailure) {
                    // The trigger already fired, and a failed pass never re-centres it — leaving it
                    // where the device just exited, so nothing can fire again. Re-rank to re-arm it.
                    logger.logMovementRearmedAfterFailedRefresh()
                    performLocalRefresh(userId, latitude, longitude, config, containmentEpoch)
                }
                remote
            } else {
                performLocalRefresh(userId, latitude, longitude, config, containmentEpoch)
            }
        } finally {
            releaseRefreshSlot()
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
            containmentEpoch = store.containmentEpoch(),
            register = manager::replaceGeofencesForBootRestore,
            // No initial-enter on boot restore: the cached anchor may be stale if the device moved
            // while off, so containment can't be trusted.
            emitInitialEnter = false,
            armPolygonsFromFix = false
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun performRemoteRefresh(
        userId: String,
        latitude: Double,
        longitude: Double,
        containmentEpoch: Long,
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
                    containmentEpoch = containmentEpoch,
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
        containmentEpoch: Long,
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff,
        emitInitialEnter: Boolean = true,
        armPolygonsFromFix: Boolean = true
    ): Result<Unit> = registerNearestAndPersist(
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        regions = store.getCachedRegions(),
        config = cachedConfig,
        containmentEpoch = containmentEpoch,
        register = register,
        emitInitialEnter = emitInitialEnter,
        armPolygonsFromFix = armPolygonsFromFix
    )

    /**
     * Default register path for Tier A / Tier B refreshes. An ID is treated
     * as skip-safe only when the cached region equals the incoming one — any
     * param drift forces a re-register so GMS doesn't keep stale values.
     * Boot restore bypasses this; OS state is empty after reboot.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun registerWithBusinessDiff(regions: List<GeofenceRegion>): Result<Unit> {
        // After a reboot or app update GMS state is gone, so re-register everything rather than
        // trust the surviving registeredIds (which would otherwise be skipped as "unchanged").
        val osStateWiped = osStateWiped()
        val existingBusinessIds = if (osStateWiped) {
            emptySet()
        } else {
            unchangedRegisteredIds(regions)
        }
        if (!osStateWiped) {
            val registeredBusinessIds = store.getRegisteredIds() - GeofenceConstants.MOVEMENT_TRIGGER_ID
            val requestedBusinessIds = regions.asSequence()
                .map(GeofenceRegion::id)
                .filter { it != GeofenceConstants.MOVEMENT_TRIGGER_ID }
                .toSet()
            val additions = requestedBusinessIds - existingBusinessIds
            val stale = (registeredBusinessIds - requestedBusinessIds).sorted()
            val projectedPeak = registeredBusinessIds.size + 1 + additions.size
            val preRemovalCount = (projectedPeak - MAX_GMS_GEOFENCES).coerceAtLeast(0)
            val preRemove = stale.take(preRemovalCount)
            if (preRemove.isNotEmpty()) {
                val removal = manager.removeGeofencesByIds(preRemove)
                if (removal.isFailure) return removal
                val remainingIds = store.getRegisteredIds() - preRemove.toSet()
                store.saveRegisteredIds(remainingIds)
                store.saveRoutableRegisteredIds(
                    store.getRoutableRegisteredIds() - preRemove.toSet()
                )
                store.saveRetainedRegisteredRegions(
                    store.getRetainedRegisteredRegions().filterNot { it.id in preRemove }
                )
                val remainingPolygonIds = remainingIds.mapNotNull { id ->
                    store.getRegisteredRegion(id)?.takeIf(GeofenceRegion::isPolygon)?.id
                }.toSet()
                polygonController?.reconcileRegisteredPolygons(remainingPolygonIds)
            }
        }
        return manager.replaceGeofences(
            regions = regions,
            existingBusinessIds = existingBusinessIds
        )
    }

    /** Business IDs already registered whose GMS-relevant params match the cache — safe to skip re-adding. */
    private fun unchangedRegisteredIds(regions: List<GeofenceRegion>): Set<String> {
        val registeredBusinessIds = store.getRegisteredIds() - GeofenceConstants.MOVEMENT_TRIGGER_ID
        val routableBusinessIds = store.getRoutableRegisteredIds() - GeofenceConstants.MOVEMENT_TRIGGER_ID
        val cachedById = store.getCachedRegions().associateBy { it.id }
        return regions
            .filter { region ->
                val cached = cachedById[region.id]
                region.id in registeredBusinessIds &&
                    region.id in routableBusinessIds &&
                    cached?.equalsForRegistration(region) == true
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
        containmentEpoch: Long,
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff,
        onRegistered: () -> Unit = {},
        emitInitialEnter: Boolean = true,
        armPolygonsFromFix: Boolean = true
    ): Result<Unit> {
        // Pure mapping + filter — no shared state, kept outside the lock.
        val pinnedPolygonIds = polygonIdsToPin(regions)
        val nearest = if (pinnedPolygonIds.isEmpty()) {
            distanceFilter.nearest(
                regions = regions,
                latitude = latitude,
                longitude = longitude,
                max = config.maxBusinessGeofences,
                maxDistanceMeters = config.maxMonitoringDistance
            )
        } else {
            distanceFilter.nearest(
                regions = regions,
                latitude = latitude,
                longitude = longitude,
                max = config.maxBusinessGeofences,
                maxDistanceMeters = config.maxMonitoringDistance,
                pinnedIds = pinnedPolygonIds
            )
        }
        // Keep the movement trigger registered even when no regions qualify right now — all beyond
        // maxMonitoringDistance, or the distance-capped /nearest returned none here — so an EXIT
        // re-ranks/re-fetches as the device travels. Only maxBusinessGeofences = 0 means "feature off".
        val monitoringEnabled = config.maxBusinessGeofences > 0
        val regionsToRegister = if (!monitoringEnabled) {
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
            val syncUserStateGeneration = store.userStateGeneration()
            fun sessionStillCurrent(): Boolean =
                secureUserStore.getUserId() == userId &&
                    store.userStateGeneration() == syncUserStateGeneration
            fun retainCleanupOnly(ids: Set<String>) {
                store.saveRegisteredIds(store.getRegisteredIds() + ids)
                store.saveRoutableRegisteredIds(emptySet())
                polygonController?.stopAll()
            }
            // Synthesis baseline, snapshotted before register/persist mutate the store. No reboot
            // override here (unlike the registration diff): callers disable synthesis outright
            // while the reboot flag is up — the anchor can predate the reboot.
            val unchangedRegistered = unchangedRegisteredIds(nearest)
            // Registered before this pass but not cache-equal: the ID survived a geometry edit.
            val previouslyRegistered = store.getRegisteredIds()
            val cachedById = store.getCachedRegions().associateBy(GeofenceRegion::id)
            val nearestById = nearest.associateBy(GeofenceRegion::id)
            val retainedById = store.getRetainedRegisteredRegions().associateBy(GeofenceRegion::id)
            val changedPolygonIds = nearest.filter { region ->
                region.isPolygon && cachedById[region.id]?.polygonVertices != region.polygonVertices
            }.mapTo(mutableSetOf(), GeofenceRegion::id)
            val reRegisteredIds = nearest.map { it.id }
                .filter { it in previouslyRegistered && it !in unchangedRegistered }
                .toSet()
            val registrationResult = register(regionsToRegister)
            if (registrationResult.isSuccess) {
                val newIds = regionsToRegister.map { it.id }.toSet()
                if (!sessionStillCurrent()) {
                    retainCleanupOnly(newIds)
                    logger.logSyncSkipped("user changed during registration")
                    return@withLock registrationResult
                }
                // Stale cleanup — Manager added new−existing, we remove
                // existing−new. Runs only on add success; on failure leave
                // previous registrations intact rather than wipe.
                val existingIds = store.getRegisteredIds()
                val staleIds = existingIds - newIds
                val staleRemovalSucceeded = if (staleIds.isNotEmpty()) {
                    manager.removeGeofencesByIds(staleIds.toList()).isSuccess
                } else {
                    true
                }
                if (!sessionStillCurrent()) {
                    retainCleanupOnly(newIds + if (staleRemovalSucceeded) emptySet() else staleIds)
                    logger.logSyncSkipped("user changed during stale cleanup")
                    return@withLock registrationResult
                }
                val idsToSave = if (staleRemovalSucceeded) {
                    newIds
                } else {
                    newIds + staleIds
                }
                val retainedStaleRegions = if (staleRemovalSucceeded) {
                    emptyList()
                } else {
                    staleIds.mapNotNull { cachedById[it] ?: retainedById[it] }
                }
                val publishRegistrationState = {
                    store.saveRegisteredIds(idsToSave)
                    store.saveRetainedRegisteredRegions(retainedStaleRegions)
                    // Publish the new catalog before any old-geometry fine detection can stage a
                    // transition after successful removal. Staging and this save share the store's
                    // transition lock, so one side wins atomically and the revision check rejects
                    // the loser.
                    onRegistered()
                    // Only the just-confirmed catalog may generate events. IDs retained solely to
                    // retry failed OS removal remain cleanup tombstones across user handoffs.
                    store.saveRoutableRegisteredIds(newIds)
                    if (!emitInitialEnter) {
                        // Reboot/app replacement wiped GMS state. Persisted coarse membership came
                        // from the old OS session and cannot be used to restart fine monitoring.
                        polygonController?.invalidatePersistedCoarseState()
                    }
                    // Seed containment from our own geometry: synthesis is suppressed after a
                    // reboot or app update, and without a record the later genuine EXIT looks
                    // unentered and gets dropped.
                    val insideIds = nearest.filter { !it.isPolygon && it.contains(latitude, longitude) }
                        .map { it.id }
                        .toSet()
                    // A moved circle keeps its ID, so containment and the reported-ENTER mark can
                    // describe where the fence used to be. Reset both, but only once the new geometry
                    // agrees the device is out, or trimming a radius manufactures a second arrival.
                    // Circle containment can be recomputed from this sync fix. Polygon edits keep
                    // their committed state until the accuracy-aware evaluator confirms the new
                    // geometry; clearing it here would manufacture a second ENTER for inside→inside.
                    val movedAwayIds = reRegisteredIds.filterTo(mutableSetOf()) { id ->
                        nearestById[id]?.isPolygon != true && id !in insideIds
                    }
                    val resetApplied = store.reconcileEnteredIds(
                        registeredIds = newIds,
                        inside = insideIds,
                        // Registration awaited GMS, so a transition since this fix is newer evidence.
                        sinceEpoch = containmentEpoch,
                        resetIds = movedAwayIds
                    )
                    // What the store actually reset, not what we asked it to: an arrival reported
                    // during the await keeps its record, and the mark that record was reported with.
                    // A fence dropped from the set never reports the EXIT that would re-arm it.
                    store.pruneEmittedEnterIds(newIds - resetApplied)
                    val registeredPolygonIds = nearest.filter(GeofenceRegion::isPolygon)
                        .mapTo(mutableSetOf(), GeofenceRegion::id)
                    polygonController?.reconcileRegisteredPolygons(registeredPolygonIds)
                    changedPolygonIds.forEach { polygonController?.resetEvidence(it) }
                    if (armPolygonsFromFix && emitInitialEnter) {
                        val committedInside = store.getEnteredIds()
                        nearest.filter { region ->
                            region.isPolygon &&
                                (
                                    region.id in committedInside ||
                                        region.distanceTo(latitude, longitude) <= region.radius
                                    )
                        }.forEach {
                            polygonController?.activate(
                                polygonId = it.id,
                                expectedUserStateGeneration = syncUserStateGeneration,
                                expectedRegionRevision = it.transitionRevision()
                            )
                        }
                    }
                    polygonController?.recover()
                    // Stamp uptime and package update time so the next refresh detects a reboot or
                    // app update (both wipe OS geofences) and re-registers instead of trusting ids.
                    store.setLastRegistrationUptime(clock.elapsedRealtime())
                    packageInfo.lastUpdateTimeMs()?.let { store.setLastRegistrationPackageUpdateTime(it) }
                    // Track the user's location at each successful registration so boot restore can
                    // re-center close to their real position. Clear only when nothing is registered
                    // (kill switch) — the trigger, and thus its location, is gone.
                    if (monitoringEnabled) {
                        store.saveLastMovementTriggerLocation(GeofenceLocation(latitude, longitude))
                    } else {
                        store.clearLastMovementTriggerLocation()
                    }
                    logger.logSyncSucceeded(nearest.size, movementTriggerRegistered = monitoringEnabled)
                }
                val published = polygonController?.publishRegistrationIfCurrent(
                    expectedUserStateGeneration = syncUserStateGeneration,
                    userId = userId,
                    publish = publishRegistrationState
                ) ?: if (sessionStillCurrent()) {
                    publishRegistrationState()
                    true
                } else {
                    false
                }
                if (!published) {
                    retainCleanupOnly(idsToSave)
                    logger.logSyncSkipped("user changed before registration publication")
                    return@withLock registrationResult
                }
            }
            if (registrationResult.isSuccess && emitInitialEnter) {
                // Identity can change during the awaited GMS call, and reset doesn't clear pending
                // delivery rows — never queue a synthetic ENTER for a signed-out/switched user.
                if (sessionStillCurrent()) {
                    emitInitialEnters(
                        nearest,
                        unchangedRegistered,
                        userId,
                        latitude,
                        longitude,
                        syncUserStateGeneration
                    )
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
     *
     * Requires the stored containment record and this fix's geometry to agree — reconcile carries a
     * record forward for a still-registered fence, so it can outlive the visit.
     */
    private suspend fun emitInitialEnters(
        candidates: List<GeofenceRegion>,
        unchangedRegisteredIds: Set<String>,
        userId: String,
        latitude: Double,
        longitude: Double,
        expectedUserStateGeneration: Long
    ) {
        val timestamp = clock.currentTimeSeconds()
        val contained = store.getEnteredIds()
        for (region in candidates) {
            if (secureUserStore.getUserId() != userId ||
                store.userStateGeneration() != expectedUserStateGeneration
            ) {
                return
            }
            if (region.isPolygon) continue
            val newlyRegistered = region.id !in unchangedRegisteredIds
            val monitorsEnter = GeofenceTransitionType.ENTER in region.transitionTypes
            // Both: a carried-forward record can outlive the visit, and geometry alone ignores an
            // EXIT reported while GMS was awaited.
            val insideNow = region.contains(latitude, longitude)
            if (!newlyRegistered || !monitorsEnter || region.id !in contained || !insideNow) continue
            logger.logInitialEnterInside(region.id)
            transitionEmitter.emitWithExpectedState(
                geofenceId = region.id,
                transition = Event.GeofenceTransition.ENTER,
                userId = userId,
                timestampSeconds = timestamp,
                geofenceName = region.name,
                metadata = region.metadata,
                geosetIds = region.geosetIds,
                monitorsExit = GeofenceTransitionType.EXIT in region.transitionTypes,
                expectedUserStateGeneration = expectedUserStateGeneration,
                expectedRegionRevision = region.transitionRevision()
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
        val resetGeneration = store.userStateGeneration()
        // Clear OS-side first. On failure, preserve store state so the next refresh's stale-cleanup
        // diff can retry removal (unremoved OS regs would otherwise orphan). Cached regions/config
        // are kept; the freshness timestamp is dropped so the next login re-fetches.
        manager.clearAll().also { result ->
            if (polygonController != null) {
                polygonController.completeUserReset(
                    expectedUserStateGeneration = resetGeneration,
                    osRegistrationsCleared = result.isSuccess
                )
            } else {
                store.completeUserReset(resetGeneration, osRegistrationsCleared = result.isSuccess)
            }
            // Wipe the departing user's cooldown history on any genuine sign-out, even if the
            // OS clear failed — keys are user-scoped, so this is data hygiene, not correctness.
            cooldownFilter.clearAll()
        }
    }

    private fun polygonIdsToPin(regions: List<GeofenceRegion>): Set<String> {
        val polygonIds = regions.filter(GeofenceRegion::isPolygon).mapTo(mutableSetOf(), GeofenceRegion::id)
        return (store.getActivePolygonIds() + store.getEnteredIds()) intersect polygonIds
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

    private companion object {
        // Long enough to outlast the slowest holder: a remote pass can spend the HTTP client's
        // connect plus read timeout (10s each) before it releases, and giving up on one that then
        // fails to register is the case that strands the trigger. Deliberately past the receiver's
        // DISPATCH_WAIT_BUDGET_MS — the receiver only bounds how long it joins, and a pass that
        // lands late still re-centres, whereas a dropped one leaves nothing to re-centre.
        val MOVEMENT_SLOT_WAIT = 30.seconds
        val MOVEMENT_SLOT_POLL = 50.milliseconds
        val MOVEMENT_SLOT_ATTEMPTS = (MOVEMENT_SLOT_WAIT / MOVEMENT_SLOT_POLL).toInt()
        const val MAX_GMS_GEOFENCES = 100
    }
}
