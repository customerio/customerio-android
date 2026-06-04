package io.customer.location.geofence.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.await
import io.customer.location.geofence.di.geofenceEventTracker
import io.customer.location.geofence.di.geofenceLogger
import io.customer.location.geofence.di.pendingGeofenceDeliveryStore
import io.customer.location.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.PendingDeliveryResult
import io.customer.sdk.data.store.claimSendRestore

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
    suspend fun schedule(entry: PendingGeofenceDelivery) {
        val input = Data.Builder()
            .putString(KEY_GEOFENCE_ID, entry.geofenceId)
            .putString(KEY_TRANSITION, entry.transition.name)
            .putLong(KEY_TIMESTAMP, entry.timestamp)
            .apply {
                entry.latitude?.let { putDouble(KEY_LATITUDE, it) }
                entry.longitude?.let { putDouble(KEY_LONGITUDE, it) }
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

        // entry.key ("${geofenceId}_${transition}_${timestamp}") doubles as the
        // unique-work name so the foreground flush can cancel this worker by key.
        // Second-precision timestamp: same-second bursts collide (KEEP dedupes them);
        // later transitions get distinct keys so an offline-queued worker can't block legitimate re-entries.
        val workManager = workManagerProvider.getWorkManager()
        if (workManager != null) {
            // Await persistence so the BroadcastReceiver doesn't finish() before WM commits the work spec.
            workManager.enqueueUniqueWork(entry.key, ExistingWorkPolicy.KEEP, workRequest).await()
        } else {
            asyncTracker.trackEvent(entry)
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
        // WorkManager may restart this worker in a cold process where the host app hasn't
        // initialized the SDK yet; without this, SDKComponent.android() would throw.
        SDKComponent.setupAndroidComponent(context = applicationContext)
        val logger = SDKComponent.geofenceLogger
        val geofenceId = inputData.getString(KEY_GEOFENCE_ID)
        val transitionName = inputData.getString(KEY_TRANSITION)
        val latitude = if (inputData.hasKeyWithValueOfType(KEY_LATITUDE, Double::class.javaObjectType)) inputData.getDouble(KEY_LATITUDE, 0.0) else null
        val longitude = if (inputData.hasKeyWithValueOfType(KEY_LONGITUDE, Double::class.javaObjectType)) inputData.getDouble(KEY_LONGITUDE, 0.0) else null
        val timestamp = inputData.getLong(KEY_TIMESTAMP, 0L)

        if (geofenceId.isNullOrEmpty() || transitionName.isNullOrEmpty()) {
            logger.logEventInvalidInput(geofenceId, transitionName)
            return Result.failure()
        }

        val transition = when (transitionName) {
            Event.GeofenceTransition.ENTER.name -> Event.GeofenceTransition.ENTER
            Event.GeofenceTransition.EXIT.name -> Event.GeofenceTransition.EXIT
            else -> {
                logger.logEventInvalidInput(geofenceId, transitionName)
                return Result.failure()
            }
        }

        val store = SDKComponent.android().pendingGeofenceDeliveryStore
        val entry = PendingGeofenceDelivery(geofenceId, transition, latitude, longitude, timestamp)

        // Shared exactly-once decision: claim before sending so we don't double
        // deliver against the foreground flush (which also claims before
        // publishing), and restore the entry on failure so a retry or the flush
        // can deliver it later.
        return when (
            val outcome = store.claimSendRestore(entry) {
                SDKComponent.android().geofenceEventTracker
                    .trackEvent(geofenceId, transition, latitude, longitude, timestamp)
            }
        ) {
            PendingDeliveryResult.AlreadyClaimed -> {
                logger.logEventDeliverySkippedAlreadyDelivered(geofenceId, transition.name)
                Result.success()
            }
            PendingDeliveryResult.Delivered -> {
                logger.logEventDelivered(geofenceId, transition.name)
                Result.success()
            }
            is PendingDeliveryResult.Retryable -> {
                logger.logEventDeliveryRetryable(geofenceId, transition.name, outcome.cause?.message)
                Result.retry()
            }
            is PendingDeliveryResult.Failed -> {
                logger.logEventDeliveryFailed(geofenceId, transition.name, outcome.cause?.message)
                Result.failure()
            }
        }
    }
}
