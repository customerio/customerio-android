package io.customer.geofence

import android.Manifest
import androidx.annotation.RequiresPermission
import io.customer.geofence.api.GeofenceApiService
import io.customer.geofence.api.toCatalogEntries
import io.customer.geofence.api.toDomainConfig
import io.customer.geofence.api.toDomainRegions
import io.customer.geofence.polygon.PolygonBootSessionProvider
import io.customer.geofence.polygon.PolygonGeofenceServiceController
import io.customer.geofence.polygon.PolygonSupport
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.getCachedConfigOrFallback
import io.customer.geofence.store.registrationsPredateBoot
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

internal interface GeofenceRepository {
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refresh(latitude: Double, longitude: Double): Result<Unit>

    /**
     * Always waits for an in-flight sync: the fix is already consumed, and unlike an anchor it can
     * judge containment. The caller must not pass a stale fix.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refreshFromLiveFix(latitude: Double, longitude: Double): Result<Unit>

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun handleMovement(
        latitude: Double,
        longitude: Double,
        movementTriggerRadius: suspend () -> Float? = { null },
        fixQuality: GeofenceFixQuality = GeofenceFixQuality.UNKNOWN
    ): Result<Unit>

    /** After a reboot. Uses the cached anchor, since no live location is available during boot. */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun restoreFromCache(): Result<Unit>

    /**
     * Drops OS geofences and user-scoped state (including cooldowns), keeping the workspace cache.
     * No-op while a user is signed in: geofences are workspace-scoped, and tearing down would race
     * the identify sync that reconciles them.
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
    private val bootSessionProvider: PolygonBootSessionProvider,
    private val packageInfo: GeofencePackageInfo,
    private val logger: GeofenceLogger,
    private val polygonController: PolygonGeofenceServiceController? = null,
    private val dwellCoordinator: GeofenceDwellCoordinator? = null,
    // Same instance the request builder and the ranker hold, so a polygon that is asked for is also
    // mapped, ranked and registered, or none of the three.
    private val polygonSupport: PolygonSupport = PolygonSupport.Disabled
) : GeofenceRepository {

    // One pass at a time: a duplicate for the in-flight session drops, others wait. Released in
    // `finally` so a failure or cancellation can't latch it.
    private val refreshInProgress = AtomicBoolean(false)

    // A pass only arms routing for its own generation, so a newer session must not treat it as theirs.
    private val inFlightUserStateGeneration = AtomicLong(NO_SESSION)

    // Serializes state writes against reset(). Never held across the API call.
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
            // Identify and app-launch fire together, so a same-session duplicate drops. An older
            // session's pass won't arm this one's routing, so dropping would let the next callback
            // remove every fence.
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

    private fun refreshAction(location: LocationCoordinates, config: GeofenceConfig): RefreshAction {
        // Re-fetch measures from the last API fetch, re-rank from the last registration. Null counts
        // as 0, within radius.
        val distanceFromLastFetch = store.getLastApiFetchLocation()
            ?.distanceTo(location.latitude, location.longitude) ?: 0f
        val restoreAnchor = store.getLastMovementTriggerLocation()
        val distanceFromLastRegistration = restoreAnchor
            ?.distanceTo(location.latitude, location.longitude) ?: 0f
        // Lazy: the costliest read here, and most wakes resolve without it.
        val cachedRegions by lazy { store.getCachedRegions() }
        // Before the `when`, which short-circuits, so a stale-cache cold start still logs it.
        logger.logStorageLoaded(regionCount = { cachedRegions.size }, hasAnchor = restoreAnchor != null)

        return when {
            isStaleInTime(config) -> RefreshAction.REMOTE
            movedBeyondFetchRadius(distanceFromLastFetch, config) -> RefreshAction.REMOTE
            isRankingStale(distanceFromLastRegistration, config) -> RefreshAction.LOCAL
            hasUnregisteredCache(cachedRegions) -> RefreshAction.LOCAL
            // Else a fresh-cache launch after a reboot or app update would SKIP: registeredIds
            // survive but GMS state doesn't.
            osStateWiped() -> RefreshAction.LOCAL
            else -> RefreshAction.SKIP
        }
    }

    private fun isStaleInTime(config: GeofenceConfig): Boolean {
        val lastSync = store.getLastSyncTimestamp() ?: return true
        return clock.currentTimeMillis() - lastSync >= config.remoteFetchRefreshExpiry
    }

    // Registered radius, not configured, since a polygon can shrink the trigger. Only a live fix trips
    // this (anchors sit at the registration center), catching a movement EXIT missed while dead.
    private fun isRankingStale(distanceFromLastRegistration: Float, config: GeofenceConfig): Boolean =
        distanceFromLastRegistration >=
            (store.getLastMovementTriggerRadius() ?: config.localRefreshTriggerRadius)

    private fun movedBeyondFetchRadius(distanceFromAnchor: Float, config: GeofenceConfig): Boolean =
        distanceFromAnchor >= config.remoteFetchRefreshTriggerRadius

    private fun hasUnregisteredCache(cachedRegions: List<GeofenceRegion>): Boolean =
        cachedRegions.isNotEmpty() &&
            (store.getRegisteredIds().isEmpty() || store.getRoutableRegisteredIds().isEmpty())

    /**
     * A reboot wipes GMS geofences while registeredIds survive. Covers a missed BOOT_COMPLETED
     * (stopped state, OEM battery managers, emulator).
     */
    private fun osStateWipedByReboot(): Boolean =
        store.registrationsPredateBoot(bootSessionProvider.currentSessionId(), clock.elapsedRealtime())

    /**
     * An app update can cancel the PendingIntent, dropping OS registrations while registeredIds
     * survive.
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

    private enum class FixSource {
        /** A fix requested for this pass. */
        LIVE,

        /** Stored coordinates from the last registration, or the OS location cache. */
        ANCHOR,

        /** The fix the OS delivered with a movement trigger. */
        MOVEMENT;

        /** An anchor proves nothing about where the device is now, so it may only rank and prune. */
        val trustsGeometry: Boolean get() = this != ANCHOR

        val isOsObserved: Boolean get() = this == MOVEMENT
    }

    /**
     * For passes that can't be dropped: an unre-centred fired trigger stops discovery until the next
     * app open, reboot or update, and a live fix is spent on arrival with nothing to re-request one.
     */
    private suspend fun awaitRefreshSlot(): Boolean {
        // Polling, not a timeout: a deadline landing between a winning CAS and the block's return
        // drops the result with the slot taken, latching it for the life of the process.
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
        movementTriggerRadius: suspend () -> Float?,
        fixQuality: GeofenceFixQuality
    ): Result<Unit> {
        // Before the slot wait, as in [refreshFromLiveFix].
        val containmentEpoch = store.containmentEpoch()
        if (!awaitRefreshSlot()) {
            logger.logSyncSkipped("refresh already in progress after waiting")
            return Result.success(Unit)
        }
        try {
            // So a same-session refresh drops instead of polling out the whole wait.
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
            // No anchor yet (first EXIT after install, clearAll or sign-out): bootstrap from the server.
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
                    movementTriggerRadiusMeters = movementTriggerRadiusMeters,
                    fixQuality = fixQuality
                )
                if (remote.isFailure) {
                    // A failed pass never re-centres the fired trigger, so nothing could fire again.
                    // Re-rank to re-arm it.
                    logger.logMovementRearmedAfterFailedRefresh()
                    performLocalRefresh(
                        userId,
                        latitude,
                        longitude,
                        config,
                        containmentEpoch,
                        FixSource.MOVEMENT,
                        movementTriggerRadiusMeters = movementTriggerRadiusMeters,
                        fixQuality = fixQuality
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
                    movementTriggerRadiusMeters = movementTriggerRadiusMeters,
                    fixQuality = fixQuality
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
        // The trigger center moves with every re-rank, the fetch anchor only on API fetches.
        val restoreAnchor = store.getLastMovementTriggerLocation()
        // Bypasses refreshAction, which logs this for the other paths.
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
            // The device may have moved while off.
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
        movementTriggerRadiusMeters: Float? = null,
        fixQuality: GeofenceFixQuality = GeofenceFixQuality.UNKNOWN
    ): Result<Unit> {
        // The request carries no user identity, so the location isn't attributable to a user.
        val syncStartedAt = clock.elapsedRealtime()
        val fetchLocation = GeofenceLocation(latitude, longitude)
        val fetchStartedAt = clock.elapsedRealtime()
        val fetchResult = apiService.fetchGeofences(fetchLocation)
        // Read here, not at the log call, so the timing excludes mapping, as on iOS.
        val fetchElapsedMillis = clock.elapsedRealtime() - fetchStartedAt
        return fetchResult.fold(
            onSuccess = { response ->
                // Before mapping, so records mapping drops are still catalogued with the raw count.
                logger.logApiFetchResult(
                    returnedCount = response.geofences.size,
                    elapsedMillis = fetchElapsedMillis,
                    catalog = { response.toCatalogEntries() }
                )
                // An unusable response throws: fail the refresh, keep current registrations, and
                // never let it escape the handler-less scope.
                val mapped = runCatching {
                    response.toDomainRegions(polygonSupport) to response.toDomainConfig()
                }.getOrElse { e ->
                    logger.logSyncFailed("response mapping failed: ${e.message}")
                    return@fold Result.failure(e)
                }
                val (regions, parsedConfig) = mapped
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
                    fixQuality = fixQuality,
                    // A null config parse must not clobber the cached value.
                    onCatalogPublished = {
                        store.saveCachedRegions(regions)
                        parsedConfig?.let { store.saveCachedConfig(it) }
                    },
                    // The pass's own generation, not a re-read: an identify mid-pass must not get its
                    // cache stamped fresh, or its refresh would SKIP and leave routing empty.
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
        movementTriggerRadiusMeters: Float? = null,
        fixQuality: GeofenceFixQuality = GeofenceFixQuality.UNKNOWN
    ): Result<Unit> = registerNearestAndPersist(
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        regions = store.getCachedRegions(),
        config = cachedConfig,
        containmentEpoch = containmentEpoch,
        register = register,
        fixSource = fixSource,
        movementTriggerRadiusMeters = movementTriggerRadiusMeters,
        fixQuality = fixQuality
    )

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun registerWithBusinessDiff(regions: List<GeofenceRegion>): Result<Unit> {
        // After a reboot or app update GMS state is gone, so don't trust the surviving registeredIds.
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
            // A geometry-changed fence is in `additions`, but its re-add replaces its slot. Stale ids
            // go first when the peak would pass the GMS limit.
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

    /**
     * Skip-safe only when the cached region equals the incoming one, so GMS never keeps stale
     * params.
     */
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
        onCatalogPublished: () -> Unit = {},
        onSyncStamped: (userStateGeneration: Long) -> Unit = {},
        movementTriggerRadiusMeters: Float? = null,
        /**
         * Time and accuracy of the pass's fix. Only a movement fix reports both, so only it can
         * prove the device outside a circle; see [GeofenceFixQuality.provesOutside].
         */
        fixQuality: GeofenceFixQuality = GeofenceFixQuality.UNKNOWN,
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
            // No distance for a polygon without a usable ring, never one from its coarse circle.
            edgeDistances = {
                nearest.mapNotNull { region ->
                    region.edgeDistanceToOrNull(latitude, longitude)?.let { region.id to it.toDouble() }
                }.toMap()
            }
        )
        // The trigger stays even when no regions qualify, so an EXIT re-ranks as the device travels.
        // Only maxBusinessGeofences = 0 means "feature off".
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
            // Synthesis baseline, before register/persist mutate the store. No reboot override: a
            // reboot re-registers with INITIAL_TRIGGER_ENTER, so the OS re-reports these itself.
            val unchangedRegistered = unchangedRegisteredIds(nearest)
            // Snapshotted for the same reason: a successful registration stamps uptime and package
            // update time, which is what clears the wipe signals this reads.
            val osStateWasWiped = osStateWiped()
            if (osStateWasWiped) {
                // A reboot or package replacement ended the prior OS monitoring session. Initial
                // triggers may establish a new visit, but the old entry cannot span this gap.
                store.clearDwellVisits()
            }
            // After a wipe a requested fix defers to the re-registration's INITIAL_TRIGGER_ENTER; a
            // movement fix is the OS's own and can't be stale, so it keeps the backstop.
            val maySynthesize = fixSource.trustsGeometry &&
                (fixSource.isOsObserved || !osStateWasWiped)
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
                // Stamped only now that GMS has confirmed the add, and whoever owns the session: a
                // callback the replaced registration produced must carry an older triggering fix.
                // Same selection the business diff sends to GMS; a wipe re-sends everything.
                val reAddedIds = if (osStateWasWiped) newIds else newIds - unchangedRegistered
                val reAddedCircles = regionsToRegister.filter {
                    it.id in reAddedIds && it.id != GeofenceConstants.MOVEMENT_TRIGGER_ID && !it.isPolygon
                }
                val registeredAtElapsedMs = clock.elapsedRealtime()
                val bootSessionId = bootSessionProvider.currentSessionId()
                fun beyondEdge(region: GeofenceRegion) = region.distanceTo(latitude, longitude) - region.radius
                store.recordRegistrationIncarnations(
                    regions = reAddedCircles,
                    registeredAtElapsedMs = registeredAtElapsedMs,
                    bootSessionId = bootSessionId,
                    // Only a recent fix clear of the edge by more than its accuracy proves the
                    // device outside, and so lets the next ENTER count as an observed entry. A
                    // point check would not: GMS reports INITIAL_TRIGGER_ENTER from its own
                    // estimate, so a coarse fix just past the edge of a fence the device is already
                    // inside becomes a claimed crossing. Dated to the registration, since a
                    // callback from the replaced one carries an earlier triggering fix.
                    outsideIds = reAddedCircles
                        .filter {
                            maySynthesize &&
                                fixQuality.provesOutside(beyondEdge(it), nowElapsedRealtimeMillis = registeredAtElapsedMs)
                        }
                        .mapTo(mutableSetOf(), GeofenceRegion::id)
                )
                // A circle kept from an earlier registration was monitored before this fix was
                // taken, so its proof dates from the fix itself and needs no age bound: GMS covers
                // the time since. Dating it to the registration instead would demote a crossing
                // GMS decided while this pass was still fetching.
                val fixTakenAt = fixQuality.fixElapsedRealtimeMillis
                if (maySynthesize && fixTakenAt != null && fixTakenAt <= registeredAtElapsedMs) {
                    store.raiseOutsideProof(
                        ids = nearest
                            .filter { !it.isPolygon && it.id in newIds && it.id !in reAddedIds && fixQuality.clearsEdge(beyondEdge(it)) }
                            .mapTo(mutableSetOf(), GeofenceRegion::id),
                        provenAtElapsedMs = fixTakenAt,
                        bootSessionId = bootSessionId
                    )
                }
                if (!sessionStillCurrent()) {
                    retainCleanupOnly(newIds)
                    logger.logSyncSkipped("user changed during registration")
                    return@withLock registrationResult
                }
                // Only on add success; on failure previous registrations stay intact.
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
                    // A stale id whose removal failed keeps firing. Its PRE-sync definition lets the
                    // receiver tell a retired fence from an uncached one.
                    store.saveRetainedRegisteredRegions(retainedStaleRegions)
                    // Before routing arms: a polygon callback that routed first would evaluate
                    // against the ring this pass just replaced.
                    onCatalogPublished()
                    // Else the first callback after identify removes every live id. newIds, not
                    // idsToSave: an id kept only to retry a failed removal must not generate events.
                    val routingArmed = store.saveRoutableRegisteredIdsIfCurrent(newIds, syncUserStateGeneration)
                    if (osStateWiped()) {
                        // Persisted coarse membership came from the OS session the wipe ended, so it
                        // cannot restart fine monitoring. Keyed to the wipe, not the fix source.
                        polygonController?.invalidatePersistedCoarseState()
                    }
                    // Circles only (polygons use the accuracy-aware evaluator), no margin: widening
                    // leaves a fence smaller than the fix unjudgable, narrowing keeps a stale mark
                    // that swallows the next arrival.
                    val insideNow = nearest.filter {
                        !it.isPolygon && it.distanceTo(latitude, longitude) <= it.radius
                    }.map { it.id }.toSet()
                    // Without a record a later genuine EXIT looks unentered and is dropped, but an
                    // anchor may describe a place the device left long ago, so it seeds nothing.
                    val insideIds = insideNow.takeIf { fixSource.trustsGeometry }
                    // A moved circle's old record and mark would suppress its new arrival; even an
                    // anchor may retire them. Polygons are excluded: this fix can't judge them.
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
                    // What the store reset, not what was asked: an arrival during the await keeps its
                    // mark. A dropped fence never reports the EXIT that would clear its mark.
                    store.pruneEmittedEnterIds(newIds - resetApplied)
                    // Stamp the new OS session before recover(), which would otherwise still see the
                    // previous session and tear down the monitor reconciliation just started.
                    store.setLastRegistrationUptime(clock.elapsedRealtime())
                    store.setLastRegistrationBootSession(bootSessionProvider.currentSessionId())
                    packageInfo.lastUpdateTimeMs()?.let { store.setLastRegistrationPackageUpdateTime(it) }
                    val registeredPolygonIds = nearest.filter(GeofenceRegion::isPolygon)
                        .mapTo(mutableSetOf(), GeofenceRegion::id)
                    polygonController?.reconcileRegisteredPolygons(registeredPolygonIds)
                    changedPolygonIds.forEach { polygonController?.resetEvidence(it) }
                    polygonController?.recover()
                    // Lets boot restore re-center near the user. Cleared only by the kill switch.
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
                        // An identify landed mid-pass. Stamping would make its refresh SKIP and never
                        // arm routing.
                        logger.logSyncSkipped("user changed before routing could be armed")
                    }
                    // idsToSave, not `nearest`: fences whose stale removal failed are still monitored.
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
     * Seeds containment so a genuine EXIT isn't dropped as unmatched. No synthesis: this runs on every
     * foreground fix, and a point check on a coarse fix would fabricate an arrival near a boundary. A
     * wrong seed fails open.
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
            // A sign-out during the pass must not reinstate the departing user's containment.
            if (secureUserStore.getUserId() != userId) {
                logger.logSyncSkipped("user changed during refresh")
                return@withLock Result.success(Unit)
            }
            val registeredIds = store.getRegisteredIds()
            val monitored = store.getCachedRegions().filter { it.id in registeredIds }
            // Circles only: a polygon's `radius` is its far larger wake circle, so a seed from it
            // would later yield an EXIT for a visit that never happened.
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
     * GMS's `INITIAL_TRIGGER_ENTER` is unreliable for a region added around a stationary device. The
     * cooldown collapses a real GMS ENTER and this one into one event.
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
        val timestampElapsedMs = clock.elapsedRealtime()
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
            if (!newlyRegistered || region.id !in contained || !insideNow) continue
            if (monitorsEnter) {
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
            if (region.tracksVisit()) {
                // Registration found the device already inside, so this is a candidate visit whose
                // entry time is unknown, never an observed crossing.
                dwellCoordinator?.onEnter(
                    region.id,
                    timestamp,
                    expectedUserStateGeneration,
                    beginsNewVisit = false,
                    enteredAtElapsedMs = timestampElapsedMs
                )
            }
        }
    }

    override suspend fun reset(): Result<Unit> = stateMutex.withLock {
        // clearIdentify clears the user store before ResetEvent, so a user here means a new sign-in
        // superseded this reset.
        val currentUserId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
        if (currentUserId != null) {
            logger.logResetSuperseded()
            return@withLock Result.success(Unit)
        }
        val resetGeneration = store.userStateGeneration()
        // OS first; on failure store state is kept so the next refresh's stale cleanup retries
        // removal. Cached regions and config survive; the freshness stamp is dropped so the next
        // login re-fetches.
        manager.clearAll().also { result ->
            if (polygonController != null) {
                polygonController.completeUserReset(
                    expectedUserStateGeneration = resetGeneration,
                    osRegistrationsCleared = result.isSuccess
                )
            } else {
                store.completeUserReset(resetGeneration, osRegistrationsCleared = result.isSuccess)
            }
            if (result.isSuccess) {
                logger.logResetCompleted()
            } else {
                logger.logResetFailed(result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")
            }
            // Even if the OS clear failed: cooldown keys are user-scoped.
            cooldownFilter.clearAll()
        }
    }

    /**
     * Polygons whose eviction would strand an EXIT: active session, committed INSIDE, or a
     * registered wake circle holding this fix (the ring may sit far inside it, no coarse ENTER
     * yet). Registered only: an unregistered one owes no EXIT, and its cap-outranking pin could
     * displace an occupied region.
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
        // Distinct from any real generation, which starts at zero and only increases.
        const val NO_SESSION = -1L

        // Outlasts a remote pass's HTTP connect plus read timeout (10s each). Longer than the
        // receiver's DISPATCH_WAIT_BUDGET_MS, which only bounds the join: a late pass still
        // re-centres, a dropped one never.
        val MOVEMENT_SLOT_WAIT = 30.seconds
        val MOVEMENT_SLOT_POLL = 50.milliseconds
        val MOVEMENT_SLOT_ATTEMPTS = (MOVEMENT_SLOT_WAIT / MOVEMENT_SLOT_POLL).toInt()
        const val MAX_GMS_GEOFENCES = GeofenceConstants.MAX_OS_GEOFENCES
    }
}
