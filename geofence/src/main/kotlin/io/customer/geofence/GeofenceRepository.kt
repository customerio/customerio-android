package io.customer.geofence

import android.Manifest
import androidx.annotation.RequiresPermission
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.toCatalogEntries
import io.customer.geofence.api.toDomainConfig
import io.customer.geofence.api.toDomainRegions
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.polygon.PolygonSupport
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.getCachedConfigOrFallback
import io.customer.location.LocationCoordinates
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Geofence sync pipeline. Entry points:
 *
 * - [refresh] — for identify / app-launch. Reuses the cached set within the freshness window
 *   (re-registering locally or skipping); otherwise fetches fresh from the API. Waits behind a
 *   pass from an older session so a rapid user switch cannot lose the new user's only refresh.
 * - [refreshFromLiveFix] — same serialized pass, for a fix the SDK asked for and received.
 * - [handleMovement] — for movement-trigger EXIT. Re-ranks the cached regions for the new
 *   location, or re-fetches once past the fetch radius.
 */
internal interface GeofenceRepository {
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refresh(latitude: Double, longitude: Double): Result<Unit>

    /**
     * [refresh] for a just-acquired fix. Always waits for an in-flight sync instead of dropping: the
     * fix is consumed either way, and unlike an anchor it may judge containment. The caller must
     * not pass a stale fix.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refreshFromLiveFix(latitude: Double, longitude: Double): Result<Unit>

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun handleMovement(
        latitude: Double,
        longitude: Double,
        movementTriggerRadius: suspend () -> Float? = { null }
    ): Result<Unit>

    /**
     * Re-registers the cached geofences with the OS after a device reboot
     * (which drops all OS-side registrations). Uses the cached anchor as the
     * effective "current location" since no real-time location is available
     * during boot. Skips when there's no user or no stored location.
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
    private val manager: GeofenceRegistrar,
    private val secureUserStore: SecureUserStore,
    private val cooldownFilter: GeofenceCooldownFilter,
    private val transitionEmitter: GeofenceTransitionEmitter,
    private val clock: Clock,
    private val packageInfo: GeofencePackageInfo,
    private val logger: GeofenceLogger,
    private val polygonController: PolygonGeofenceServiceController? = null,
    // Same instance the request builder and the ranker hold, so a polygon that is asked for is also
    // mapped, ranked and registered, or none of the three.
    private val polygonSupport: PolygonSupport = PolygonSupport.Disabled
) : GeofenceRepository {

    // One sync pass at a time. A duplicate refresh for the session already in flight drops; every
    // other caller waits for the slot. Released in `finally` so a failure or cancellation can't
    // latch the gate.
    private val refreshInProgress = AtomicBoolean(false)

    // Which user session the pass holding the slot is running for. A pass can only arm routing for
    // its own generation, so a caller from a newer session must not treat it as covering theirs.
    private val inFlightUserStateGeneration = AtomicLong(NO_SESSION)

    // Serializes state-mutation against reset() (sign-out). Held only around the
    // write block — the long-running API call happens outside the lock.
    private val stateMutex = Mutex()

    private fun tryTakeRefreshSlot(): Boolean = refreshInProgress.compareAndSet(false, true)

    private fun releaseRefreshSlot() {
        inFlightUserStateGeneration.set(NO_SESSION)
        refreshInProgress.set(false)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun refresh(latitude: Double, longitude: Double): Result<Unit> {
        val containmentEpoch = store.containmentEpoch()
        val userStateGeneration = store.userStateGeneration()
        if (!tryTakeRefreshSlot()) {
            // Identify and app-launch fire together on the same anchor, so a duplicate drops. A pass
            // from an older session won't arm routing for this one, so dropping would leave routing
            // empty and the next callback would remove every live fence. Wait instead.
            if (userStateGeneration == inFlightUserStateGeneration.get()) {
                logger.logSyncSkipped("refresh already in progress")
                return Result.success(Unit)
            }
            if (!awaitRefreshSlot()) {
                logger.logSyncSkipped("refresh already in progress after waiting")
                return Result.success(Unit)
            }
        }
        inFlightUserStateGeneration.set(userStateGeneration)
        return runRefresh(latitude, longitude, FixSource.ANCHOR, containmentEpoch)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun refreshFromLiveFix(latitude: Double, longitude: Double): Result<Unit> {
        // Captured with the fix, before the slot wait: a transition reported while this pass queues
        // is newer evidence than the fix and must outrank its geometry.
        val containmentEpoch = store.containmentEpoch()
        if (!awaitRefreshSlot()) {
            logger.logSyncSkipped("refresh already in progress after waiting")
            return Result.success(Unit)
        }
        inFlightUserStateGeneration.set(store.userStateGeneration())
        return runRefresh(latitude, longitude, FixSource.LIVE, containmentEpoch)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun runRefresh(
        latitude: Double,
        longitude: Double,
        fixSource: FixSource,
        containmentEpoch: Long
    ): Result<Unit> {
        try {
            val userId = secureUserStore.getUserId()
            if (userId.isNullOrBlank()) {
                logger.logSyncSkipped("no identified user")
                return Result.success(Unit)
            }

            val config = store.getCachedConfigOrFallback()
            // Under stateMutex so a concurrent sign-out reset can't wipe state right after this
            // reads pre-wipe freshness and SKIPs. Pref reads only; network stays outside the lock.
            val action = stateMutex.withLock { refreshAction(LocationCoordinates(latitude, longitude), config) }
            return when (action) {
                RefreshAction.REMOTE -> {
                    val remote = performRemoteRefresh(userId, latitude, longitude, containmentEpoch, fixSource)
                    // With nothing routable, fences stay registered but unroutable until a pass
                    // completes. Re-rank from the cache so an offline start still routes.
                    if (remote.isFailure &&
                        store.getRoutableRegisteredIds().isEmpty() &&
                        store.getCachedRegions().isNotEmpty()
                    ) {
                        performLocalRefresh(userId, latitude, longitude, config, containmentEpoch, fixSource)
                    }
                    remote
                }
                RefreshAction.LOCAL -> performLocalRefresh(userId, latitude, longitude, config, containmentEpoch, fixSource)
                RefreshAction.SKIP -> {
                    logger.logSyncSkippedFresh()
                    reconcileContainment(userId, latitude, longitude, containmentEpoch, fixSource)
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
        val restoreAnchor = store.getLastMovementTriggerLocation()
        val distanceFromLastRegistration = restoreAnchor
            ?.distanceTo(location.latitude, location.longitude) ?: 0f
        // Lazy: deserializing the cached list is the costliest read here, and most wakes resolve on
        // the first arm below without it.
        val cachedRegions by lazy { store.getCachedRegions() }
        // Before the `when`, which short-circuits, so a stale-cache cold start still logs it.
        logger.logStorageLoaded(regionCount = { cachedRegions.size }, hasAnchor = restoreAnchor != null)

        return when {
            isStaleInTime(config) -> RefreshAction.REMOTE
            movedBeyondFetchRadius(distanceFromLastFetch, config) -> RefreshAction.REMOTE
            isRankingStale(distanceFromLastRegistration, config) -> RefreshAction.LOCAL
            hasUnregisteredCache(cachedRegions) -> RefreshAction.LOCAL
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

    // The device has left the trigger circle since the last ranking, so re-rank locally. Compared
    // against the registered radius, not the configured one, since a polygon can shrink the trigger.
    //
    // Only a live fix can trip this: anchored passes sit at the stored registration center
    // (distance 0). That is how a movement EXIT missed while the process was dead gets caught.
    private fun isRankingStale(distanceFromLastRegistration: Float, config: GeofenceConfig): Boolean =
        distanceFromLastRegistration >=
            (store.getLastMovementTriggerRadius() ?: config.localRefreshTriggerRadius)

    // The cached set only covers the area around the last fetch; once the device moves past the fetch
    // radius the set is no longer "nearby", so re-fetch from the server.
    private fun movedBeyondFetchRadius(distanceFromAnchor: Float, config: GeofenceConfig): Boolean =
        distanceFromAnchor >= config.remoteFetchRefreshTriggerRadius

    /** Cache holds regions but OS or current-session routing state is absent → re-register. */
    private fun hasUnregisteredCache(cachedRegions: List<GeofenceRegion>): Boolean =
        cachedRegions.isNotEmpty() &&
            (store.getRegisteredIds().isEmpty() || store.getRoutableRegisteredIds().isEmpty())

    /**
     * Uptime regressed since the last registration → the device rebooted, which wipes GMS geofences
     * even though registeredIds survive. Covers a missed BOOT_COMPLETED (stopped state, OEM battery
     * managers, emulator).
     */
    private fun osStateWipedByReboot(): Boolean =
        store.getLastRegistrationUptime()?.let { clock.elapsedRealtime() < it } ?: false

    /**
     * Package replaced since the last registration: an app update can cancel the geofence
     * PendingIntent, silently dropping OS registrations while registeredIds survive.
     */
    private fun osStateWipedByAppUpdate(): Boolean {
        val current = packageInfo.lastUpdateTimeMs() ?: return false
        // No stamp but live registrations means they predate stamping, which only an app update
        // can cause, so treat as wiped.
        val stamped = store.getLastRegistrationPackageUpdateTime()
            ?: return store.getRegisteredIds().isNotEmpty()
        return current != stamped
    }

    private fun osStateWiped(): Boolean = osStateWipedByReboot() || osStateWipedByAppUpdate()

    /**
     * How far a pass's geometry can be trusted.
     *
     * [LIVE] and [MOVEMENT] both carry a real fix, so either may seed containment, reset
     * moved-circle records and synthesize initial ENTERs. [ANCHOR] is stored coordinates describing
     * where the device once was, so it may only rank and prune; the next real fix owns the reseed.
     */
    private enum class FixSource {
        /** A fix requested for this pass. */
        LIVE,

        /** Stored coordinates from the last registration, or the OS location cache. */
        ANCHOR,

        /** The fix the OS delivered with a movement trigger, because the device moved. */
        MOVEMENT;

        /** An anchor proves nothing about where the device is now, so it may not judge containment. */
        val trustsGeometry: Boolean get() = this != ANCHOR

        /** True when the OS produced this fix by observing the device, not when we asked for it. */
        val isOsObserved: Boolean get() = this == MOVEMENT
    }

    /**
     * Takes the in-flight slot for a pass that can't be dropped, waiting for the holder instead.
     * For a movement pass, the OS holds a fired trigger in the outside state, so returning without
     * re-centring it stops discovery until the next app open, reboot or update; for a live fix, the
     * fix is spent on arrival and nothing re-requests one.
     */
    private suspend fun awaitRefreshSlot(): Boolean {
        // Bounded polling, not a timeout: a cancellation deadline can land between a winning CAS and
        // the block's completion, discarding the result with the slot still taken, which latches it
        // for the life of the process. Here only a caller that got `true` can hold the slot.
        repeat(MOVEMENT_SLOT_ATTEMPTS) {
            if (tryTakeRefreshSlot()) return true
            delay(MOVEMENT_SLOT_POLL)
        }
        return tryTakeRefreshSlot()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun handleMovement(
        latitude: Double,
        longitude: Double,
        movementTriggerRadius: suspend () -> Float?
    ): Result<Unit> {
        // Before the slot wait, as in [refreshFromLiveFix].
        val containmentEpoch = store.containmentEpoch()
        if (!awaitRefreshSlot()) {
            logger.logSyncSkipped("refresh already in progress after waiting")
            return Result.success(Unit)
        }
        try {
            // So a same-session refresh recognises this holder and drops instead of polling out the
            // whole wait.
            inFlightUserStateGeneration.set(store.userStateGeneration())
            val userId = secureUserStore.getUserId()
            if (userId.isNullOrBlank()) {
                logger.logSyncSkipped("no identified user")
                return Result.success(Unit)
            }

            // Resolved here, inside the job, rather than by the caller: this awaits GMS, and a
            // process killed before the job exists would lose the refresh with the EXIT spent.
            val movementTriggerRadiusMeters = movementTriggerRadius()
            val anchor = store.getLastApiFetchLocation()
            val config = store.getCachedConfigOrFallback()
            val distanceFromAnchor = anchor?.distanceTo(latitude, longitude) ?: 0f
            // No anchor yet (first EXIT after install / clearAll / sign-out) bootstraps from the server.
            // Otherwise, a non-remote move always re-ranks locally — that's the floor for any EXIT.
            val needsRemoteFetch = anchor == null ||
                movedBeyondFetchRadius(distanceFromAnchor, config)
            // The triggering fix is the OS's own, so a reboot/app-update wipe can't make it stale.
            return if (needsRemoteFetch) {
                val remote = performRemoteRefresh(
                    userId,
                    latitude,
                    longitude,
                    containmentEpoch,
                    FixSource.MOVEMENT,
                    movementTriggerRadiusMeters = movementTriggerRadiusMeters
                )
                if (remote.isFailure) {
                    // The trigger already fired, and a failed pass never re-centres it — leaving it
                    // where the device just exited, so nothing can fire again. Re-rank to re-arm it.
                    logger.logMovementRearmedAfterFailedRefresh()
                    performLocalRefresh(
                        userId,
                        latitude,
                        longitude,
                        config,
                        containmentEpoch,
                        FixSource.MOVEMENT,
                        movementTriggerRadiusMeters = movementTriggerRadiusMeters
                    )
                }
                remote
            } else {
                performLocalRefresh(
                    userId,
                    latitude,
                    longitude,
                    config,
                    containmentEpoch,
                    FixSource.MOVEMENT,
                    movementTriggerRadiusMeters = movementTriggerRadiusMeters
                )
            }
        } finally {
            releaseRefreshSlot()
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun restoreFromCache(): Result<Unit> {
        // Bypasses the in-flight gate and the business diff: the reboot wiped GMS, so persisted
        // registeredIds can't justify skipping anything. stateMutex serializes the writes.
        val userId = secureUserStore.getUserId()
        if (userId.isNullOrBlank()) {
            logger.logSyncSkipped("no identified user")
            return Result.success(Unit)
        }
        // Prefer the last movement-trigger center: it moves with every local re-rank, while the
        // fetch anchor moves only on API fetches. Fall back to the anchor when there is none yet.
        val restoreAnchor = store.getLastMovementTriggerLocation()
        // This path bypasses refreshAction, so it logs its own record. The count is lazy: nothing
        // else here needs the region list, so with diagnostics off it is never read.
        logger.logStorageLoaded(
            regionCount = { store.getCachedRegions().size },
            hasAnchor = restoreAnchor != null
        )
        val effectiveLocation = restoreAnchor ?: store.getLastApiFetchLocation()
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
            // The cached location may be stale if the device moved while off, so this pass seeds
            // nothing and synthesizes nothing.
            fixSource = FixSource.ANCHOR,
            register = manager::replaceGeofencesForBootRestore
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun performRemoteRefresh(
        userId: String,
        latitude: Double,
        longitude: Double,
        containmentEpoch: Long,
        fixSource: FixSource,
        movementTriggerRadiusMeters: Float? = null
    ): Result<Unit> {
        // The device location lets the backend return the nearby set; the request carries no user
        // identity, so it isn't attributable to a user.
        val syncStartedAt = clock.elapsedRealtime()
        val fetchLocation = GeofenceLocation(latitude, longitude)
        val fetchStartedAt = clock.elapsedRealtime()
        val fetchResult = apiService.fetchGeofences(fetchLocation)
        // Read here, not at the log call, so the timing excludes mapping, as on iOS.
        val fetchElapsedMillis = clock.elapsedRealtime() - fetchStartedAt
        return fetchResult.fold(
            onSuccess = { response ->
                // Before mapping, so records that mapping drops (or a response that fails to map)
                // are still catalogued, with the raw wire count.
                logger.logApiFetchResult(
                    returnedCount = response.geofences.size,
                    elapsedMillis = fetchElapsedMillis,
                    catalog = { response.toCatalogEntries() }
                )
                // An unusable response throws (see toDomainRegions) — fail the refresh and
                // keep current registrations; never let it escape the handler-less scope.
                val mapped = runCatching {
                    response.toDomainRegions(polygonSupport) to response.toDomainConfig()
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
                    fixSource = fixSource,
                    syncStartedAt = syncStartedAt,
                    movementTriggerRadiusMeters = movementTriggerRadiusMeters,
                    // Cache only on remote fetch; local passes reuse it. A null config parse must
                    // not clobber the previously cached value.
                    onCatalogPublished = {
                        store.saveCachedRegions(regions)
                        parsedConfig?.let { store.saveCachedConfig(it) }
                    },
                    // Keyed to the pass's own generation, not a re-read: an identify landing
                    // mid-pass must not have its cache stamped fresh by this pass, or its refresh
                    // would SKIP and leave routing empty.
                    onSyncStamped = { userStateGeneration ->
                        val stamped = store.saveApiFetchStateIfCurrent(
                            location = GeofenceLocation(latitude, longitude),
                            syncTimestamp = clock.currentTimeMillis(),
                            expectedUserStateGeneration = userStateGeneration
                        )
                        if (!stamped) {
                            logger.logSyncSkipped("cache left stale for a session that changed mid-pass")
                        }
                    }
                )
            },
            onFailure = { error ->
                logger.logApiFetchFailed(error.message)
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
        fixSource: FixSource,
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff,
        movementTriggerRadiusMeters: Float? = null
    ): Result<Unit> = registerNearestAndPersist(
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        regions = store.getCachedRegions(),
        config = cachedConfig,
        containmentEpoch = containmentEpoch,
        register = register,
        fixSource = fixSource,
        movementTriggerRadiusMeters = movementTriggerRadiusMeters
    )

    /**
     * Default register path for local and remote refreshes. An ID is treated
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
            // What the add actually grows the OS set by. A registered fence whose geometry changed
            // is in `additions` but re-adding it replaces its slot rather than taking a new one.
            // Stale ids are removed first when the peak would pass the GMS limit.
            val netNewIds = additions - registeredBusinessIds
            val projectedPeak = registeredBusinessIds.size + 1 + netNewIds.size
            val preRemovalCount = (projectedPeak - MAX_GMS_GEOFENCES).coerceAtLeast(0)
            val preRemove = stale.take(preRemovalCount)
            if (preRemove.isNotEmpty()) {
                val removal = manager.removeGeofencesByIds(preRemove)
                if (removal.isFailure) return removal
                // Persisted now, not on add success like the stale cleanup below: GMS has already
                // dropped these, and the store must mirror the OS whatever the add does next.
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
        fixSource: FixSource,
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff,
        /**
         * Publishes the freshly fetched catalog. Runs before routing arms, so a polygon callback
         * can't route and then evaluate against the ring it replaced; [onSyncStamped] runs after.
         */
        onCatalogPublished: () -> Unit = {},
        /** Marks the cache fresh, but only for the session that actually fetched it. */
        onSyncStamped: (userStateGeneration: Long) -> Unit = {},
        movementTriggerRadiusMeters: Float? = null,
        // Measured from the caller's entry so `ms=` spans the same work as on iOS.
        syncStartedAt: Long = clock.elapsedRealtime()
    ): Result<Unit> {
        val pinnedPolygonIds = polygonIdsToPin(regions, latitude, longitude)
        // Both overloads cap at GeofenceConstants.MAX_OS_BUSINESS_GEOFENCE_SLOTS, so the movement
        // trigger prepended below always has an OS slot left even when many polygons are pinned.
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
        logger.logRankEvaluated(
            candidates = regions.size,
            selectedCount = nearest.size,
            selected = { nearest.map { it.id } },
            evicted = {
                val nearestIds = nearest.map { it.id }.toSet()
                regions.map { it.id }.filterNot { it in nearestIds }
            },
            // A polygon without a usable ring reports no distance rather than one from its coarse
            // circle. Re-deriving the ring only costs anything when diagnostics are enabled.
            edgeDistances = {
                nearest.mapNotNull { region ->
                    region.edgeDistanceToOrNull(latitude, longitude)?.let { region.id to it.toDouble() }
                }.toMap()
            }
        )
        // Keep the movement trigger registered even when no regions qualify right now, so an EXIT
        // re-ranks/re-fetches as the device travels. Only maxBusinessGeofences = 0 means "feature off".
        val monitoringEnabled = config.maxBusinessGeofences > 0
        val regionsToRegister = if (!monitoringEnabled) {
            emptyList()
        } else {
            listOf(
                buildMovementTrigger(
                    latitude,
                    longitude,
                    movementTriggerRadiusMeters ?: config.localRefreshTriggerRadius
                )
            ) + nearest
        }
        return stateMutex.withLock {
            // Sign-out or a user switch may have happened during the API call.
            val currentUserId = secureUserStore.getUserId()
            if (currentUserId != userId) {
                logger.logSyncSkipped("user changed during refresh")
                return@withLock Result.success(Unit)
            }
            // Snapshot for the routing arm below. beginUserSession bumps this under its own lock, so
            // an identify landing after this point invalidates the write rather than racing it.
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
            // override (unlike the registration diff): a reboot re-registers with
            // INITIAL_TRIGGER_ENTER, so the OS re-reports these itself.
            val unchangedRegistered = unchangedRegisteredIds(nearest)
            // Snapshotted for the same reason: a successful registration stamps uptime and package
            // update time, which is what clears the wipe signals this reads.
            val osStateWasWiped = osStateWiped()
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
                // Stale cleanup: remove existing−new. Only on add success; on failure previous
                // registrations are left intact rather than wiped.
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
                    // A stale id whose removal failed keeps firing after the backend deleted it.
                    // Retaining its last definition (from the PRE-sync catalog, since the new one
                    // won't have it) lets the receiver tell a retired fence from an uncached one.
                    store.saveRetainedRegisteredRegions(retainedStaleRegions)
                    // Before routing arms: a polygon callback that routed first would evaluate
                    // against the ring this pass just replaced.
                    onCatalogPublished()
                    // Without this the first callback after an identify (which wrote an empty
                    // routable set) treats every live ID as unknown and removes it. newIds, not
                    // idsToSave: an id kept only to retry a failed removal must not generate events.
                    val routingArmed = store.saveRoutableRegisteredIdsIfCurrent(newIds, syncUserStateGeneration)
                    if (osStateWiped()) {
                        // Persisted coarse membership came from the OS session the wipe ended, so it
                        // cannot restart fine monitoring. Keyed to the wipe, not the fix source.
                        polygonController?.invalidatePersistedCoarseState()
                    }
                    // Circles only: a polygon's containment belongs to the accuracy-aware evaluator.
                    // An exact point check with no accuracy margin: widening it would leave a fence
                    // smaller than the fix unjudgable (no initial-ENTER backstop); narrowing it would
                    // keep a stale mark that swallows the next genuine arrival.
                    val insideNow = nearest.filter {
                        !it.isPolygon && it.distanceTo(latitude, longitude) <= it.radius
                    }.map { it.id }.toSet()
                    // Without a record a later genuine EXIT looks unentered and is dropped, but an
                    // anchor may describe a place the device left long ago, so it seeds nothing.
                    val insideIds = insideNow.takeIf { fixSource.trustsGeometry }
                    // A moved circle keeps its ID, so its old record and mark would suppress GMS's
                    // arrival at the new one; even an anchor may retire them. Polygons are excluded:
                    // they are absent from insideNow because this fix can't judge them.
                    val movedAwayIds = reRegisteredIds.filterNot {
                        nearestById[it]?.isPolygon == true
                    }.toSet() - insideNow
                    val resetApplied = store.reconcileEnteredIds(
                        registeredIds = newIds,
                        inside = insideIds,
                        // Registration awaited GMS, so a transition since this fix is newer evidence.
                        sinceEpoch = containmentEpoch,
                        resetIds = movedAwayIds
                    )
                    // What the store actually reset, not what was asked: an arrival reported during
                    // the await keeps its record and mark. A fence dropped from the set never
                    // reports the EXIT that would clear its mark, so it is pruned.
                    store.pruneEmittedEnterIds(newIds - resetApplied)
                    // Stamp the new OS session before recover(), which would otherwise still see the
                    // previous session and tear down the monitor reconciliation just started.
                    store.setLastRegistrationUptime(clock.elapsedRealtime())
                    packageInfo.lastUpdateTimeMs()?.let { store.setLastRegistrationPackageUpdateTime(it) }
                    val registeredPolygonIds = nearest.filter(GeofenceRegion::isPolygon)
                        .mapTo(mutableSetOf(), GeofenceRegion::id)
                    polygonController?.reconcileRegisteredPolygons(registeredPolygonIds)
                    changedPolygonIds.forEach { polygonController?.resetEvidence(it) }
                    polygonController?.recover()
                    // Lets boot restore re-center near the user's real position. Cleared only when
                    // nothing is registered (kill switch).
                    if (monitoringEnabled) {
                        store.saveLastMovementTriggerLocation(
                            GeofenceLocation(latitude, longitude),
                            movementTriggerRadiusMeters ?: config.localRefreshTriggerRadius
                        )
                    } else {
                        store.clearLastMovementTriggerLocation()
                    }
                    if (routingArmed) {
                        onSyncStamped(syncUserStateGeneration)
                    } else {
                        // An identify landed mid-pass. Leaving the stamp stale sends the new user's
                        // refresh down the remote path; stamping it would make that refresh SKIP and
                        // never arm routing.
                        logger.logSyncSkipped("user changed before routing could be armed")
                    }
                    // idsToSave, not `nearest`: fences whose stale removal failed are still monitored.
                    // The trigger is reported separately.
                    val monitoredBusinessIds = idsToSave.filterNot { it == GeofenceConstants.MOVEMENT_TRIGGER_ID }
                    logger.logSyncSucceeded(
                        monitoredBusinessIds.size,
                        movementTriggerRegistered = monitoringEnabled,
                        elapsedMillis = clock.elapsedRealtime() - syncStartedAt
                    )
                    logger.logRegionsRegisteredIds(
                        ids = monitoredBusinessIds.sorted(),
                        movementTriggerId = GeofenceConstants.MOVEMENT_TRIGGER_ID
                            .takeIf { idsToSave.contains(it) }
                    )
                    if (monitoringEnabled) {
                        logger.logMovementTriggerRegistered(
                            latitude = latitude,
                            longitude = longitude,
                            radiusMeters = (
                                movementTriggerRadiusMeters ?: config.localRefreshTriggerRadius
                                ).toDouble()
                        )
                    }
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
            // An anchor proves nothing about where the device is now. After a wipe, synthesis for a
            // requested fix defers to the re-registration's own INITIAL_TRIGGER_ENTER; a movement
            // fix is the OS's own and can't be stale, so it keeps the backstop.
            val maySynthesize = fixSource.trustsGeometry &&
                (fixSource.isOsObserved || !osStateWasWiped)
            if (registrationResult.isSuccess && maySynthesize) {
                // Identity can change during the awaited GMS call, and reset doesn't clear pending
                // delivery rows, so never queue a synthetic ENTER for a signed-out/switched user.
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
     * Seeds containment from a fix whose registration is already current, so a genuine EXIT isn't
     * later dropped as unmatched. Registration and the cache are left alone.
     *
     * Seeding only, no synthesis: this runs on every foreground fix, and a point check on a coarse
     * fix would fabricate an arrival near a boundary. A wrong seed fails open (it
     * lets an EXIT through).
     */
    private suspend fun reconcileContainment(
        userId: String,
        latitude: Double,
        longitude: Double,
        containmentEpoch: Long,
        fixSource: FixSource
    ): Result<Unit> {
        if (!fixSource.trustsGeometry) return Result.success(Unit)
        return stateMutex.withLock {
            // A sign-out during the pass must not have this write reinstate the departing user's
            // containment.
            if (secureUserStore.getUserId() != userId) {
                logger.logSyncSkipped("user changed during refresh")
                return@withLock Result.success(Unit)
            }
            val registeredIds = store.getRegisteredIds()
            val monitored = store.getCachedRegions().filter { it.id in registeredIds }
            // Circles only: a polygon's `radius` is its wake circle, far larger than the ring, so a
            // point-in-circle seed would later yield an EXIT for a visit that never happened and
            // spend that fence's duplicate-event cooldown.
            val insideNow = monitored
                .filter { !it.isPolygon && it.distanceTo(latitude, longitude) <= it.radius }
                .map { it.id }
                .toSet()
            store.reconcileEnteredIds(
                registeredIds = registeredIds,
                inside = insideNow,
                sinceEpoch = containmentEpoch
            )
            logger.logContainmentJudged(insideNow.size)
            Result.success(Unit)
        }
    }

    /**
     * Synthesizes an ENTER for each newly-registered fence the device is already inside: GMS's
     * `INITIAL_TRIGGER_ENTER` is unreliable for a region added around a stationary device.
     * "New" = not in [unchangedRegisteredIds]. Cooldown-deduped, so a real GMS ENTER and this one
     * collapse to one event.
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
            // Record and geometry must agree: a carried-forward record can outlive the visit, and
            // geometry alone ignores an EXIT reported while GMS was awaited.
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
            logger.logResetSuperseded()
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
            // Keyed on the OS clear, not on which reset path ran above: both paths share the
            // outcome these records report.
            if (result.isSuccess) {
                logger.logResetCompleted()
            } else {
                logger.logResetFailed(result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")
            }
            // Even if the OS clear failed: cooldown keys are user-scoped, so this is data hygiene,
            // not correctness.
            cooldownFilter.clearAll()
        }
    }

    /**
     * Polygons whose eviction would strand an outstanding business EXIT: an active fine session, a
     * committed INSIDE, or a REGISTERED wake circle containing this fix (the ring it ranks by can
     * sit far inside that circle, and no coarse ENTER may have been observed yet).
     *
     * The containment check counts only registered circles: an unregistered polygon cannot owe an
     * EXIT, and since pins outrank the server cap, pinning it could displace a region the device is
     * standing in.
     */
    private fun polygonIdsToPin(
        regions: List<GeofenceRegion>,
        latitude: Double,
        longitude: Double
    ): Set<String> {
        val polygons = regions.filter(GeofenceRegion::isPolygon)
        val polygonIds = polygons.mapTo(mutableSetOf(), GeofenceRegion::id)
        val statefulIds = (store.getActivePolygonIds() + store.getEnteredIds()) intersect polygonIds
        val registeredIds = store.getRegisteredIds()
        val containingRegisteredIds = polygons
            .filter { it.id in registeredIds && it.distanceTo(latitude, longitude) <= it.radius }
            .mapTo(mutableSetOf(), GeofenceRegion::id)
        return statefulIds + containingRegisteredIds
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
        // No pass holds the slot. Distinct from any real generation, which starts at zero and only
        // ever increases.
        const val NO_SESSION = -1L

        // Outlasts the slowest holder: a remote pass can spend the HTTP connect plus read timeout
        // (10s each) before releasing. Past the receiver's DISPATCH_WAIT_BUDGET_MS on purpose: that
        // only bounds the join, and a late pass still re-centres while a dropped one never does.
        val MOVEMENT_SLOT_WAIT = 30.seconds
        val MOVEMENT_SLOT_POLL = 50.milliseconds
        val MOVEMENT_SLOT_ATTEMPTS = (MOVEMENT_SLOT_WAIT / MOVEMENT_SLOT_POLL).toInt()
        const val MAX_GMS_GEOFENCES = GeofenceConstants.MAX_OS_GEOFENCES
    }
}
