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
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION])
    suspend fun refresh(latitude: Double, longitude: Double): Result<Unit>
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

    // Serializes state-mutation against future state-clearing (reset() lands in
    // the services PR for sign-out). Held only around the write block — the
    // long-running API call happens outside the lock.
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

                        // Remove geofences that were registered last time but aren't in the new set.
                        // Without this, OS-side registrations accumulate across refreshes until we
                        // hit the 100-per-app limit and new registrations silently fail.
                        val previousRegions = store.getAll()
                        val newIds = toRegister.map { it.id }.toSet()
                        val staleRegions = previousRegions.filter { it.id !in newIds }
                        val staleRemovalSucceeded = if (staleRegions.isNotEmpty()) {
                            // Failures here are logged by the manager; don't block the fresh
                            // registration — we keep the unremoved entries in the persisted set
                            // below so the next refresh's diff retries their cleanup.
                            manager.removeGeofencesByIds(staleRegions.map { it.id }).isSuccess
                        } else {
                            true
                        }

                        store.setLastSyncTimestamp(System.currentTimeMillis())

                        manager.addGeofences(toRegister).also { result ->
                            if (result.isSuccess) {
                                // Persist what we just registered. If stale-cleanup failed, keep
                                // the unremoved stale entries too so the next refresh's diff sees
                                // them and retries — otherwise they'd orphan in the OS forever.
                                val toPersist = if (staleRemovalSucceeded) {
                                    toRegister
                                } else {
                                    toRegister + staleRegions
                                }
                                store.saveAll(toPersist)
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
