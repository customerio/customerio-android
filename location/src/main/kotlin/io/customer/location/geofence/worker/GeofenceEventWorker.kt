package io.customer.location.geofence.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import io.customer.location.geofence.di.geofenceEventTracker
import io.customer.location.geofence.di.geofenceLogger
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import java.io.IOException

private const val KEY_GEOFENCE_ID = "geofence_id"
private const val KEY_TRANSITION = "transition"
private const val KEY_LATITUDE = "latitude"
private const val KEY_LONGITUDE = "longitude"
private const val KEY_TIMESTAMP = "timestamp"

/**
 * Schedules a [GeofenceEventWorker] for guaranteed delivery of a geofence transition event.
 * Falls back to in-process async HTTP if WorkManager is unavailable (does not survive death).
 */
internal class GeofenceEventScheduler(
    private val workManagerProvider: CustomerIOWorkManagerProvider,
    private val asyncTracker: AsyncGeofenceEventTracker
) {
    fun schedule(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        latitude: Double?,
        longitude: Double?,
        timestamp: Long
    ) {
        val input = Data.Builder()
            .putString(KEY_GEOFENCE_ID, geofenceId)
            .putString(KEY_TRANSITION, transition.name)
            .putLong(KEY_TIMESTAMP, timestamp)
            .apply {
                if (latitude != null) putDouble(KEY_LATITUDE, latitude)
                if (longitude != null) putDouble(KEY_LONGITUDE, longitude)
            }
            .build()

        val workRequest = OneTimeWorkRequestBuilder<GeofenceEventWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(input)
            .addTag(WORK_MANAGER_TAG_CIO)
            .addTag(WORK_MANAGER_TAG_GEOFENCE)
            .build()

        val uniqueKey = "${geofenceId}_${transition.name}_$timestamp"

        val workManager = workManagerProvider.getWorkManager()
        if (workManager != null) {
            workManager.enqueueUniqueWork(uniqueKey, ExistingWorkPolicy.KEEP, workRequest)
        } else {
            asyncTracker.trackEvent(geofenceId, transition, latitude, longitude, timestamp)
        }
    }

    private companion object {
        const val WORK_MANAGER_TAG_CIO = "cio-requests"
        const val WORK_MANAGER_TAG_GEOFENCE = "cio-geofence"
    }
}

/** Worker that sends a geofence transition event via direct HTTP, surviving process death. */
internal class GeofenceEventWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val logger = SDKComponent.geofenceLogger
        val geofenceId = inputData.getString(KEY_GEOFENCE_ID)
        val transitionName = inputData.getString(KEY_TRANSITION)
        val latitude = if (inputData.hasKeyWithValueOfType(KEY_LATITUDE, Double::class.javaObjectType)) inputData.getDouble(KEY_LATITUDE, 0.0) else null
        val longitude = if (inputData.hasKeyWithValueOfType(KEY_LONGITUDE, Double::class.javaObjectType)) inputData.getDouble(KEY_LONGITUDE, 0.0) else null
        val timestamp = inputData.getLong(KEY_TIMESTAMP, 0L)

        if (geofenceId.isNullOrEmpty() || transitionName.isNullOrEmpty()) {
            logger.logEventInvalidInput()
            return Result.failure()
        }

        val transition = when (transitionName) {
            Event.GeofenceTransition.ENTER.name -> Event.GeofenceTransition.ENTER
            Event.GeofenceTransition.EXIT.name -> Event.GeofenceTransition.EXIT
            else -> {
                logger.logEventInvalidInput()
                return Result.failure()
            }
        }

        val result = SDKComponent.android().geofenceEventTracker
            .trackEvent(geofenceId, transition, latitude, longitude, timestamp)

        return when {
            result.isSuccess -> Result.success()
            result.exceptionOrNull() is IOException -> {
                logger.logEventDeliveryRetryable(geofenceId, transition.name)
                Result.retry()
            }
            else -> {
                logger.logEventDeliveryFailed(geofenceId, transition.name, result.exceptionOrNull()?.message)
                Result.failure()
            }
        }
    }
}
