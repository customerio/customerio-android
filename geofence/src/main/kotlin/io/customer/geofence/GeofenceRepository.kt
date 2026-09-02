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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Geofence sync pipeline. Two public entry points:
 *
 * - [refresh] — for identify / app-launch. Reuses the cached set within the freshness window
 *   (re-registering locally or skipping); otherwise fetches fresh from the API.
 * - [refreshFromLiveFix] — same pass, for a fix the SDK asked for and received.
 * - [handleMovement] — for movement-trigger EXIT. Re-ranks the cached regions for the new
 *   location.
 */
internal interface GeofenceRepository {
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refresh(latitude: Double, longitude: Double): Result<Unit>

    /**
     * [refresh] for a just-acquired fix, which waits for an in-flight sync instead of dropping.
     * Identify and app-launch collide constantly and share one anchor, so dropping loses nothing;
     * this caller holds the device's real position and the fix is consumed either way. Like the
     * movement trigger, it carries a real fix and is trusted accordingly — see [FixSource].
     *
     * [quality] qualifies that trust: a fix too old to say where the device is now ranks and
     * prunes like an anchor instead of judging containment.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun refreshFromLiveFix(
        latitude: Double,
        longitude: Double,
        quality: GeofenceFixQuality = GeofenceFixQuality.UNKNOWN
    ): Result<Unit>

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
    private val logger: GeofenceLogger
) : GeofenceRepository {

    // One sync pass at a time. A pass carrying its own live fix waits for the slot rather than
    // dropping — see [awaitRefreshSlot]; [refresh] drops, because identify and app-launch fire
    // together on the same anchor and only one needs to run. Released in `finally` so a failure
    // or cancellation can't latch the gate.
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
        val containmentEpoch = store.containmentEpoch()
        if (!tryTakeRefreshSlot()) {
            logger.logSyncSkipped("refresh already in progress")
            return Result.success(Unit)
        }
        return runRefresh(latitude, longitude, FixSource.ANCHOR, containmentEpoch)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override suspend fun refreshFromLiveFix(
        latitude: Double,
        longitude: Double,
        quality: GeofenceFixQuality
    ): Result<Unit> {
        // Captured with the fix, before the slot wait: a transition reported while this pass queues
        // is newer evidence than the fix and must outrank its geometry.
        val containmentEpoch = store.containmentEpoch()
        // Judged with the epoch above, before the slot wait: both belong to the fix, not the pass.
        val now = clock.elapsedRealtime()
        val fixSource = if (quality.isFresh(now)) {
            FixSource.LIVE
        } else {
            logger.logStaleFixDemoted(
                now - (quality.fixElapsedRealtimeMillis ?: now),
                GeofenceConstants.MAX_LIVE_FIX_AGE_MS
            )
            FixSource.ANCHOR
        }
        if (!awaitRefreshSlot()) {
            logger.logSyncSkipped("refresh already in progress after waiting")
            return Result.success(Unit)
        }
        return runRefresh(latitude, longitude, fixSource, containmentEpoch)
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
            // Decided under stateMutex so a concurrent sign-out reset can't wipe state right
            // after this reads pre-wipe freshness/registrations and SKIPs — that would leave
            // a just-identified user unmonitored. Pref reads only; network stays outside the lock.
            val action = stateMutex.withLock { refreshAction(LocationCoordinates(latitude, longitude), config) }
            return when (action) {
                RefreshAction.REMOTE -> performRemoteRefresh(userId, latitude, longitude, containmentEpoch, fixSource)
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
        // Before the slot wait, as in [refreshFromLiveFix].
        val containmentEpoch = store.containmentEpoch()
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
            val distanceFromAnchor = anchor?.distanceTo(latitude, longitude) ?: 0f
            // No anchor yet (first EXIT after install / clearAll / sign-out) bootstraps from the server.
            // Otherwise, a non-remote move always re-ranks locally — that's the floor for any EXIT.
            val needsRemoteFetch = anchor == null ||
                movedBeyondFetchRadius(distanceFromAnchor, config)
            // The triggering fix is the OS's own, so a reboot/app-update wipe can't make it stale.
            return if (needsRemoteFetch) {
                val remote = performRemoteRefresh(userId, latitude, longitude, containmentEpoch, FixSource.MOVEMENT)
                if (remote.isFailure) {
                    // The trigger already fired, and a failed pass never re-centres it — leaving it
                    // where the device just exited, so nothing can fire again. Re-rank to re-arm it.
                    logger.logMovementRearmedAfterFailedRefresh()
                    performLocalRefresh(userId, latitude, longitude, config, containmentEpoch, FixSource.MOVEMENT)
                }
                remote
            } else {
                performLocalRefresh(userId, latitude, longitude, config, containmentEpoch, FixSource.MOVEMENT)
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
            // The cached location may be stale if the device moved while off.
            fixSource = FixSource.ANCHOR
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun performRemoteRefresh(
        userId: String,
        latitude: Double,
        longitude: Double,
        containmentEpoch: Long,
        fixSource: FixSource
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
                    fixSource = fixSource,
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
        fixSource: FixSource,
        register: suspend (List<GeofenceRegion>) -> Result<Unit> = ::registerWithBusinessDiff
    ): Result<Unit> = registerNearestAndPersist(
        userId = userId,
        latitude = latitude,
        longitude = longitude,
        regions = store.getCachedRegions(),
        config = cachedConfig,
        containmentEpoch = containmentEpoch,
        register = register,
        fixSource = fixSource
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
        val existingBusinessIds = if (osStateWiped()) {
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
        containmentEpoch: Long,
        fixSource: FixSource,
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
            // Synthesis baseline, snapshotted before register/persist mutate the store. No reboot
            // override here (unlike the registration diff): a reboot re-registers with
            // INITIAL_TRIGGER_ENTER, so the OS re-reports these itself.
            val unchangedRegistered = unchangedRegisteredIds(nearest)
            // Snapshotted for the same reason: a successful registration stamps uptime and package
            // update time, which is what clears the wipe signals this reads.
            val osStateWasWiped = osStateWiped()
            // Registered before this pass but not cache-equal: the ID survived a geometry edit.
            val previouslyRegistered = store.getRegisteredIds()
            val reRegisteredIds = nearest.map { it.id }
                .filter { it in previouslyRegistered && it !in unchangedRegistered }
                .toSet()
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
                    // An exact point check, with no accuracy margin in either direction. Widening
                    // the inside test would make a fence smaller than the fix unjudgable, so the
                    // initial-ENTER backstop would never fire; narrowing the outside test would keep
                    // a stale mark on a fence the device is just outside of, and that mark swallows
                    // the next genuine arrival as redundant.
                    val insideNow = nearest.filter { it.distanceTo(latitude, longitude) <= it.radius }
                        .map { it.id }
                        .toSet()
                    // Without a record the later genuine EXIT looks unentered and gets dropped, but
                    // an anchor would seed a place the device left days ago — so it judges nothing.
                    val insideIds = insideNow.takeIf { fixSource.trustsGeometry }
                    // A moved circle keeps its ID, so its record and mark can describe a fence that
                    // no longer exists, and the mark then suppresses GMS's own arrival at the new
                    // one. An anchor may retire evidence it can't vouch for; it just may not seed.
                    val movedAwayIds = reRegisteredIds - insideNow
                    val resetApplied = store.reconcileEnteredIds(
                        registeredIds = idsToSave,
                        inside = insideIds,
                        // Registration awaited GMS, so a transition since this fix is newer evidence.
                        sinceEpoch = containmentEpoch,
                        resetIds = movedAwayIds
                    )
                    // What the store actually reset, not what we asked it to: an arrival reported
                    // during the await keeps its record, and the mark that record was reported with.
                    // A fence dropped from the set never reports the EXIT that would re-arm it.
                    store.pruneEmittedEnterIds(idsToSave - resetApplied)
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
                    onRegistered()
                    logger.logSyncSucceeded(nearest.size, movementTriggerRegistered = monitoringEnabled)
                }
            }
            // An anchor inside a fence proves nothing about where the device is now, so a record
            // carried across a geometry edit must not become a fresh arrival. After a wipe a
            // requested fix may also describe a stretch we never observed, so synthesis defers to
            // the re-registration's own INITIAL_TRIGGER_ENTER — but only for a fix we asked for. A
            // movement fix is the OS's own, produced because the device just moved, so no wipe can
            // have made it stale, and suppressing it here would drop the backstop on the one path
            // that exists because that callback gets missed.
            val maySynthesize = fixSource.trustsGeometry &&
                (fixSource.isOsObserved || !osStateWasWiped)
            if (registrationResult.isSuccess && maySynthesize) {
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
     * Judges containment from a fix whose registration is already current.
     *
     * The anchor pass that made the inputs look fresh could not judge geometry, so without this the
     * live fix queued behind it is discarded and the entered set stays unseeded — the receiver then
     * reads a genuine EXIT as unmatched and drops it. Registration and the cache are left alone.
     *
     * Seeding only. Synthesis stays tied to a pass that registers: this one runs on every foreground
     * fix, and an exact point check on a fix that can be 100 m out would fabricate an arrival near a
     * boundary, then leave a mark that swallows the real one. A wrong seed is the fail-open
     * direction — it lets an EXIT through — so it does not carry that cost.
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
            // As in registerNearestAndPersist: a sign-out during the pass must not have this write
            // reinstate the departing user's containment.
            if (secureUserStore.getUserId() != userId) {
                logger.logSyncSkipped("user changed during refresh")
                return@withLock Result.success(Unit)
            }
            val registeredIds = store.getRegisteredIds()
            val monitored = store.getCachedRegions().filter { it.id in registeredIds }
            val insideNow = monitored
                .filter { it.distanceTo(latitude, longitude) <= it.radius }
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
        longitude: Double
    ) {
        val timestamp = clock.currentTimeSeconds()
        val contained = store.getEnteredIds()
        candidates.forEach { region ->
            val newlyRegistered = region.id !in unchangedRegisteredIds
            val monitorsEnter = GeofenceTransitionType.ENTER in region.transitionTypes
            // Both: a carried-forward record can outlive the visit, and geometry alone ignores an
            // EXIT reported while GMS was awaited.
            val insideNow = region.distanceTo(latitude, longitude) <= region.radius
            if (!newlyRegistered || !monitorsEnter || region.id !in contained || !insideNow) return@forEach
            logger.logInitialEnterInside(region.id)
            transitionEmitter.emit(
                geofenceId = region.id,
                transition = Event.GeofenceTransition.ENTER,
                userId = userId,
                timestampSeconds = timestamp,
                geofenceName = region.name,
                metadata = region.metadata,
                geosetIds = region.geosetIds,
                monitorsExit = GeofenceTransitionType.EXIT in region.transitionTypes
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

    private companion object {
        // Long enough to outlast the slowest holder: a remote pass can spend the HTTP client's
        // connect plus read timeout (10s each) before it releases, and giving up on one that then
        // fails to register is the case that strands the trigger. Deliberately past the receiver's
        // DISPATCH_WAIT_BUDGET_MS — the receiver only bounds how long it joins, and a pass that
        // lands late still re-centres, whereas a dropped one leaves nothing to re-centre.
        val MOVEMENT_SLOT_WAIT = 30.seconds
        val MOVEMENT_SLOT_POLL = 50.milliseconds
        val MOVEMENT_SLOT_ATTEMPTS = (MOVEMENT_SLOT_WAIT / MOVEMENT_SLOT_POLL).toInt()
    }
}
