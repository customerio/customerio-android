package io.customer.geofence.polygon

import android.content.Context
import android.location.Location
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.await
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.polygonBootSessionProvider
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingPolygonApproachBatch
import io.customer.geofence.store.PendingPolygonApproachLocation
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal class PolygonApproachWorkScheduler(
    private val workManagerProvider: CustomerIOWorkManagerProvider,
    private val store: GeofenceRegionStore,
    private val bootSessionProvider: PolygonBootSessionProvider
) {
    suspend fun enqueue(locations: List<Location>, userStateGeneration: Long): Boolean {
        if (locations.isEmpty()) return true
        val workManager = workManagerProvider.getWorkManager() ?: return false
        val bootSessionId = bootSessionProvider.currentSessionId()
        val batches = locations.chunked(MAXIMUM_LOCATIONS_PER_BATCH).map { locationsInBatch ->
            PendingPolygonApproachBatch(
                id = UUID.randomUUID().toString(),
                userStateGeneration = userStateGeneration,
                bootSessionId = bootSessionId,
                locations = locationsInBatch.map(Location::toPendingApproachLocation)
            )
        }
        if (!store.appendPendingPolygonApproachBatches(batches)) return false

        return try {
            batches.forEach {
                val request = OneTimeWorkRequestBuilder<PolygonApproachWorker>()
                    .addTag(WORK_MANAGER_TAG_CIO)
                    .addTag(WORK_MANAGER_TAG_POLYGON_APPROACH)
                    .build()
                workManager.enqueueUniqueWork(
                    ORDERED_POLYGON_APPROACH_QUEUE,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                ).await()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A partially-created chain must not later replay a batch that the receiver processes
            // directly as its fallback.
            batches.forEach { store.removePendingPolygonApproachBatch(it.id) }
            false
        }
    }

    internal companion object {
        const val ORDERED_POLYGON_APPROACH_QUEUE = "cio-polygon-approach-queue"
        const val WORK_MANAGER_TAG_CIO = "cio-requests"
        const val WORK_MANAGER_TAG_POLYGON_APPROACH = "cio-polygon-approach"
        const val MAXIMUM_LOCATIONS_PER_BATCH = 32
    }
}

internal class PolygonApproachWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        SDKComponent.setupAndroidComponent(context = applicationContext)
        val store = SDKComponent.android().geofenceRegionStore
        val batch = store.getPendingPolygonApproachBatches().firstOrNull() ?: return Result.success()
        if (batch.bootSessionId != SDKComponent.android().polygonBootSessionProvider.currentSessionId()) {
            return if (store.removePendingPolygonApproachBatch(batch.id)) {
                Result.success()
            } else {
                Result.retry()
            }
        }
        return try {
            PolygonApproachReceiver().handleLocations(
                locations = batch.locations.map(PendingPolygonApproachLocation::toLocation),
                expectedUserStateGeneration = batch.userStateGeneration
            )
            if (store.removePendingPolygonApproachBatch(batch.id)) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SDKComponent.geofenceLogger.logPolygonApproachMonitoringFailed(e.message)
            if (runAttemptCount + 1 < MAXIMUM_ATTEMPTS) {
                Result.retry()
            } else {
                // A poison batch cannot cancel the dependent chain and strand newer locations.
                if (store.removePendingPolygonApproachBatch(batch.id)) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        }
    }

    private companion object {
        const val MAXIMUM_ATTEMPTS = 3
    }
}

private fun Location.toPendingApproachLocation() = PendingPolygonApproachLocation(
    latitude = latitude,
    longitude = longitude,
    accuracy = if (hasAccuracy()) accuracy else null,
    speed = if (hasSpeed()) speed else null,
    timestampMillis = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos
)

private fun PendingPolygonApproachLocation.toLocation() = Location(PROVIDER).apply {
    latitude = this@toLocation.latitude
    longitude = this@toLocation.longitude
    this@toLocation.accuracy?.let { accuracy = it }
    this@toLocation.speed?.let { speed = it }
    time = timestampMillis
    elapsedRealtimeNanos = this@toLocation.elapsedRealtimeNanos
}

private const val PROVIDER = "cio-polygon-approach"
