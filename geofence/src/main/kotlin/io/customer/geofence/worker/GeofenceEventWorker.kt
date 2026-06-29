package io.customer.geofence.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.hasKeyWithValueOfType
import io.customer.geofence.di.geofenceEventTracker
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.PendingDeliveryResult
import io.customer.sdk.data.store.claimSendRestore
import java.util.UUID

private const val KEY_GEOFENCE_ID = "geofence_id"
private const val KEY_GEOFENCE_NAME = "geofence_name"
private const val KEY_TRANSITION = "transition"
private const val KEY_TRANSITION_ID = "transition_id"
private const val KEY_TIMESTAMP = "timestamp"
private const val KEY_USER_ID = "user_id"

// Testing-only (geofence-testing branch): trigger context carried through the worker path.
private const val KEY_TRIGGER_LAT = "trigger_latitude"
private const val KEY_TRIGGER_LNG = "trigger_longitude"
private const val KEY_DISTANCE = "distance_meters"
private const val KEY_RADIUS = "geofence_radius"

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
            .putString(KEY_TRANSITION_ID, entry.transitionId)
            .putLong(KEY_TIMESTAMP, entry.timestamp)
            .apply {
                entry.userId?.let { putString(KEY_USER_ID, it) }
                entry.geofenceName?.let { putString(KEY_GEOFENCE_NAME, it) }
                // Testing-only (geofence-testing branch).
                entry.triggerLatitude?.let { putDouble(KEY_TRIGGER_LAT, it) }
                entry.triggerLongitude?.let { putDouble(KEY_TRIGGER_LNG, it) }
                entry.distanceMeters?.let { putDouble(KEY_DISTANCE, it) }
                entry.geofenceRadius?.let { putDouble(KEY_RADIUS, it) }
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

        val userId = inputData.getString(KEY_USER_ID)
        val store = SDKComponent.android().pendingGeofenceDeliveryStore
        val entry = PendingGeofenceDelivery(
            geofenceId = geofenceId,
            transition = transition,
            timestamp = timestamp,
            userId = userId,
            // Always set by the scheduler; fall back to a fresh id so an unexpected miss still delivers.
            transitionId = inputData.getString(KEY_TRANSITION_ID) ?: UUID.randomUUID().toString(),
            geofenceName = inputData.getString(KEY_GEOFENCE_NAME),
            // Testing-only (geofence-testing branch).
            triggerLatitude = inputData.getDoubleOrNull(KEY_TRIGGER_LAT),
            triggerLongitude = inputData.getDoubleOrNull(KEY_TRIGGER_LNG),
            distanceMeters = inputData.getDoubleOrNull(KEY_DISTANCE),
            geofenceRadius = inputData.getDoubleOrNull(KEY_RADIUS)
        )

        // No identified user at queue time — direct HTTP needs a userId, so
        // leave the entry in the store for the foreground flush instead.
        if (userId.isNullOrEmpty()) {
            logger.logEventDeliveryDeferredAnonymous(geofenceId, transition.name)
            return Result.success()
        }

        // Shared exactly-once decision: claim before sending so we don't double
        // deliver against the foreground flush (which also claims before
        // publishing), and restore the entry on failure so a retry or the flush
        // can deliver it later.
        return when (
            val outcome = store.claimSendRestore(entry) {
                SDKComponent.android().geofenceEventTracker.trackEvent(entry)
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

// Testing-only (geofence-testing branch): Data has no nullable getter, so distinguish
// "absent" from a real 0.0 via the typed key check.
private fun androidx.work.Data.getDoubleOrNull(key: String): Double? =
    if (hasKeyWithValueOfType<Double>(key)) getDouble(key, 0.0) else null
