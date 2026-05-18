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
 * Single entry point for the geofence sync pipeline: fetch nearby regions for the
 * current user/location, persist them, then register the closest N with the OS.
 */
internal interface GeofenceRepository {
    /**
     * @param force when true, bypasses the [GeofenceConstants.STALE_THRESHOLD_MS]
     *   freshness check. Use for triggers that need a guaranteed re-fetch (e.g.
     *   movement-trigger EXIT, where the trigger's center must be updated).
     *   When false, a recent successful sync short-circuits the API call.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    suspend fun refresh(latitude: Double, longitude: Double, force: Boolean = false): Result<Unit>

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

    // Dedup gate: if a refresh is already running, drop the duplicate request so
    // we don't burn a redundant API call. Released in `finally` so a failure or
    // cancellation doesn't permanently latch the gate.
    private val refreshInProgress = AtomicBoolean(false)

    // Serializes state-mutation against reset() (sign-out). Held only around the
    // write block — the long-running API call happens outside the lock.
    private val stateMutex = Mutex()

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    override suspend fun refresh(latitude: Double, longitude: Double, force: Boolean): Result<Unit> {
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

            if (!force) {
                val lastSync = store.getLastSyncTimestamp()
                if (lastSync != null && System.currentTimeMillis() - lastSync < GeofenceConstants.STALE_THRESHOLD_MS) {
                    logger.logSyncSkippedFresh()
                    return Result.success(Unit)
                }
            }

            return apiService.fetchGeofences(userId, latitude, longitude).fold(
                onSuccess = { response ->
                    // Pure mapping + filter — no shared state, kept outside the lock.
                    val regions = response.toDomainRegions()
                    val config = response.toDomainConfig()

                    val nearest = distanceFilter.nearest(
                        regions = regions,
                        latitude = latitude,
                        longitude = longitude,
                        max = config.maxBusinessGeofences
                    )
                    // Zero business geofences => register nothing (no movement trigger either).
                    // Customers without configured geofences pay zero runtime cost.
                    val toRegister = if (nearest.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(buildMovementTrigger(latitude, longitude, config.movementTriggerRadius)) + nearest
                    }
                    stateMutex.withLock {
                        // Recheck userId — sign-out or user switch may have happened
                        // during the API call. Without this we'd write the previous
                        // user's geofences for a signed-out / different user.
                        val currentUserId = secureUserStore.getUserId()
                        if (currentUserId != userId) {
                            logger.logSyncSkipped("user changed during refresh")
                            return@withLock Result.success(Unit)
                        }

                        manager.addGeofences(toRegister).also { result ->
                            if (result.isSuccess) {
                                // Stale cleanup runs ONLY after add succeeds. If add fails we
                                // leave previous OS registrations intact — better to keep
                                // serving the last-known-good set than to wipe everything and
                                // be unable to recover until the next refresh.
                                val previousRegions = store.getAll()
                                val newIds = toRegister.map { it.id }.toSet()
                                val staleRegions = previousRegions.filter { it.id !in newIds }
                                val staleRemovalSucceeded = if (staleRegions.isNotEmpty()) {
                                    // Failures are logged by the manager; we preserve the
                                    // unremoved entries in the persisted set below so the next
                                    // refresh's diff retries their cleanup.
                                    manager.removeGeofencesByIds(staleRegions.map { it.id }).isSuccess
                                } else {
                                    true
                                }
                                // Persist what we just registered. If stale-cleanup failed, keep
                                // the unremoved stale entries too so the next refresh's diff sees
                                // them and retries — otherwise they'd orphan in the OS forever.
                                val toPersist = if (staleRemovalSucceeded) {
                                    toRegister
                                } else {
                                    toRegister + staleRegions
                                }
                                store.saveAll(toPersist)
                                // Timestamp marks the last end-to-end-successful sync (API + OS).
                                // If registration failed, we deliberately leave it stale so the
                                // next external trigger (identify / app launch) past the threshold
                                // retries instead of being skipped as "fresh".
                                store.setLastSyncTimestamp(System.currentTimeMillis())
                                logger.logSyncSucceeded(nearest.size)
                            }
                        }
                    }
                },
                onFailure = { error ->
                    logger.logSyncFailed(error.message)
                    Result.failure(error)
                }
            )
        } finally {
            refreshInProgress.set(false)
        }
    }

    override suspend fun reset(): Result<Unit> = stateMutex.withLock {
        // Serialised against in-flight refresh state writes so a sign-out can't
        // interleave with a partially-completed registration and leave us in an
        // inconsistent state.
        //
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
