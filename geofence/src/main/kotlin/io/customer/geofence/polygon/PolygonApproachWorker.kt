package io.customer.geofence.polygon

import android.content.Context
import android.location.Location
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.await
import io.customer.geofence.di.geofenceLogger
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider

internal class PolygonApproachWorkScheduler(
    private val workManagerProvider: CustomerIOWorkManagerProvider
) {
    suspend fun enqueue(locations: List<Location>, userStateGeneration: Long): Boolean {
        val workManager = workManagerProvider.getWorkManager() ?: return false
        PolygonApproachWorkCodec.encode(locations, userStateGeneration).forEach { input ->
            val request = OneTimeWorkRequestBuilder<PolygonApproachWorker>()
                .setInputData(input)
                .addTag(WORK_MANAGER_TAG_CIO)
                .addTag(WORK_MANAGER_TAG_POLYGON_APPROACH)
                .build()
            workManager.enqueueUniqueWork(
                ORDERED_POLYGON_APPROACH_QUEUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            ).await()
        }
        return true
    }

    internal companion object {
        const val ORDERED_POLYGON_APPROACH_QUEUE = "cio-polygon-approach-queue"
        const val WORK_MANAGER_TAG_CIO = "cio-requests"
        const val WORK_MANAGER_TAG_POLYGON_APPROACH = "cio-polygon-approach"
    }
}

internal class PolygonApproachWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        SDKComponent.setupAndroidComponent(context = applicationContext)
        val decoded = PolygonApproachWorkCodec.decode(inputData)
        if (decoded == null) {
            SDKComponent.geofenceLogger.logPolygonApproachMonitoringFailed("invalid durable location batch")
            return Result.failure()
        }
        return try {
            PolygonApproachReceiver().handleLocations(
                locations = decoded.locations,
                expectedUserStateGeneration = decoded.userStateGeneration
            )
            Result.success()
        } catch (e: Exception) {
            SDKComponent.geofenceLogger.logPolygonApproachMonitoringFailed(e.message)
            if (runAttemptCount + 1 < MAXIMUM_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAXIMUM_ATTEMPTS = 3
    }
}

internal object PolygonApproachWorkCodec {
    private const val KEY_USER_STATE_GENERATION = "user_state_generation"
    private const val KEY_LATITUDES = "latitudes"
    private const val KEY_LONGITUDES = "longitudes"
    private const val KEY_ACCURACIES = "accuracies"
    private const val KEY_HAS_ACCURACY = "has_accuracy"
    private const val KEY_SPEEDS = "speeds"
    private const val KEY_HAS_SPEED = "has_speed"
    private const val KEY_TIMESTAMPS = "timestamps"
    private const val KEY_ELAPSED_REALTIME_NANOS = "elapsed_realtime_nanos"
    private const val MAXIMUM_LOCATIONS_PER_WORK = 32

    data class DecodedBatch(
        val locations: List<Location>,
        val userStateGeneration: Long
    )

    fun encode(locations: List<Location>, userStateGeneration: Long): List<Data> =
        locations.chunked(MAXIMUM_LOCATIONS_PER_WORK).map { batch ->
            Data.Builder()
                .putLong(KEY_USER_STATE_GENERATION, userStateGeneration)
                .putDoubleArray(KEY_LATITUDES, batch.map(Location::getLatitude).toDoubleArray())
                .putDoubleArray(KEY_LONGITUDES, batch.map(Location::getLongitude).toDoubleArray())
                .putFloatArray(KEY_ACCURACIES, batch.map(Location::getAccuracy).toFloatArray())
                .putBooleanArray(KEY_HAS_ACCURACY, batch.map(Location::hasAccuracy).toBooleanArray())
                .putFloatArray(KEY_SPEEDS, batch.map(Location::getSpeed).toFloatArray())
                .putBooleanArray(KEY_HAS_SPEED, batch.map(Location::hasSpeed).toBooleanArray())
                .putLongArray(KEY_TIMESTAMPS, batch.map(Location::getTime).toLongArray())
                .putLongArray(
                    KEY_ELAPSED_REALTIME_NANOS,
                    batch.map(Location::getElapsedRealtimeNanos).toLongArray()
                )
                .build()
        }

    fun decode(data: Data): DecodedBatch? {
        val latitudes = data.getDoubleArray(KEY_LATITUDES) ?: return null
        val longitudes = data.getDoubleArray(KEY_LONGITUDES) ?: return null
        val accuracies = data.getFloatArray(KEY_ACCURACIES) ?: return null
        val hasAccuracy = data.getBooleanArray(KEY_HAS_ACCURACY) ?: return null
        val speeds = data.getFloatArray(KEY_SPEEDS) ?: return null
        val hasSpeed = data.getBooleanArray(KEY_HAS_SPEED) ?: return null
        val timestamps = data.getLongArray(KEY_TIMESTAMPS) ?: return null
        val elapsedRealtimeNanos = data.getLongArray(KEY_ELAPSED_REALTIME_NANOS) ?: return null
        val size = latitudes.size
        if (
            size == 0 ||
            listOf(
                longitudes.size,
                accuracies.size,
                hasAccuracy.size,
                speeds.size,
                hasSpeed.size,
                timestamps.size,
                elapsedRealtimeNanos.size
            ).any { it != size }
        ) {
            return null
        }
        val generation = data.getLong(KEY_USER_STATE_GENERATION, Long.MIN_VALUE)
        if (generation == Long.MIN_VALUE) return null
        val locations = List(size) { index ->
            Location(PROVIDER).apply {
                latitude = latitudes[index]
                longitude = longitudes[index]
                if (hasAccuracy[index]) accuracy = accuracies[index]
                if (hasSpeed[index]) speed = speeds[index]
                time = timestamps[index]
                this.elapsedRealtimeNanos = elapsedRealtimeNanos[index]
            }
        }
        return DecodedBatch(locations, generation)
    }

    private const val PROVIDER = "cio-polygon-approach"
}
