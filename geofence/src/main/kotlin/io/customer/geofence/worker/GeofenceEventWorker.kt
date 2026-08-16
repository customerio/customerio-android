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
import io.customer.geofence.di.geofenceEventTracker
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.PendingDeliveryResult
import io.customer.sdk.data.store.sendRemoveOnSuccess

private const val KEY_ENTRY_KEY = "entry_key"

/**
 * Schedules a [GeofenceEventWorker] for guaranteed delivery of a geofence transition event.
 * Falls back to in-process async HTTP if WorkManager is unavailable (does not survive death).
 */
internal class GeofenceEventScheduler(
    private val workManagerProvider: CustomerIOWorkManagerProvider,
    private val asyncTracker: AsyncGeofenceEventTracker
) {
    suspend fun schedule(entry: PendingGeofenceDelivery) {
        // Only the store key rides through inputData; the worker loads the full row from the pending
        // store. WorkManager caps Data at ~10 KB (a Binder/DB limit), so inlining the row — whose
        // metadata can be large — risks an enqueue failure. The store already holds the authoritative
        // snapshot, so carrying a copy would only risk drift anyway.
        val input = Data.Builder()
            .putString(KEY_ENTRY_KEY, entry.key)
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

        // entry.key doubles as the unique-work name so the foreground flush can cancel this worker
        // by key. Its stable transition ID makes recovery idempotent without conflating distinct
        // crossings that happen in the same second.
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
        val entryKey = inputData.getString(KEY_ENTRY_KEY)
        if (entryKey.isNullOrEmpty()) {
            logger.logEventInvalidInput(entryKey, null)
            return Result.failure()
        }

        // The receiver persists the row before scheduling, so it's normally present. A miss means the
        // foreground flush already delivered and removed it — nothing to do.
        val store = SDKComponent.android().pendingGeofenceDeliveryStore
        val entry = store.get(entryKey) ?: store.loadAll().firstOrNull { it.legacyKey == entryKey }
        if (entry == null) {
            logger.logEventWorkerEntryMissing(entryKey)
            return Result.success()
        }

        // Shouldn't happen — the receiver drops anonymous transitions before
        // persisting. Defensive: leave the row rather than send a track the
        // backend would reject.
        if (entry.userId.isNullOrEmpty()) {
            logger.logEventDeliveryDeferredAnonymous(entry.geofenceId, entry.transition.name)
            return Result.success()
        }

        // At-least-once delivery: keep the row until the send is confirmed, so a process death
        // mid-request leaves it for a WorkManager retry or the foreground flush rather than
        // dropping it. The duplicate this can produce (overlap with the flush, or a retry after
        // an ambiguous success) is deduped backend-side via the stable transitionId.
        return when (
            val outcome = store.sendRemoveOnSuccess(entry) {
                SDKComponent.android().geofenceEventTracker.trackEvent(entry)
            }
        ) {
            PendingDeliveryResult.AlreadyClaimed -> {
                logger.logEventDeliverySkippedAlreadyDelivered(entry.geofenceId, entry.transition.name)
                Result.success()
            }
            PendingDeliveryResult.Delivered -> {
                logger.logEventDelivered(entry.geofenceId, entry.transition.name)
                Result.success()
            }
            is PendingDeliveryResult.Retryable -> {
                logger.logEventDeliveryRetryable(entry.geofenceId, entry.transition.name, outcome.cause?.message)
                Result.retry()
            }
            is PendingDeliveryResult.Failed -> {
                logger.logEventDeliveryFailed(entry.geofenceId, entry.transition.name, outcome.cause?.message)
                Result.failure()
            }
        }
    }
}
