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
                    stateMutex.withLock {
                        // Recheck userId — sign-out or user switch may have happened
                        // during the API call. Without this we'd write the previous
                        // user's geofences for a signed-out / different user.
                        val currentUserId = secureUserStore.getUserId()
                        if (currentUserId != userId) {
                            logger.logSyncSkipped("user changed during refresh")
                            return@withLock Result.success(Unit)
                        }
                        store.saveAll(regions)
                        store.setLastSyncTimestamp(System.currentTimeMillis())
                        manager.addGeofences(nearest).also { result ->
                            if (result.isSuccess) logger.logSyncSucceeded(nearest.size)
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
}
