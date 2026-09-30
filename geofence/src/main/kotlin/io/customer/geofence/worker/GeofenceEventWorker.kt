package io.customer.geofence.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.await
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.di.geofenceEventTracker
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import io.customer.sdk.core.network.HttpRequestFailure
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.PendingDeliveryResult
import io.customer.sdk.data.store.sendRemoveOnSuccess
import java.io.IOException

private const val ORDERED_GEOFENCE_DELIVERY_QUEUE = "cio-geofence-delivery-queue"

internal class GeofenceEventScheduler(
    private val workManagerProvider: CustomerIOWorkManagerProvider,
    private val asyncTracker: AsyncGeofenceEventTracker
) {
    suspend fun schedule(entry: PendingGeofenceDelivery) {
        val workRequest = OneTimeWorkRequestBuilder<GeofenceEventWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_MANAGER_TAG_CIO)
            .addTag(WORK_MANAGER_TAG_GEOFENCE)
            .build()

        // One durable chain so a later EXIT can't overtake an earlier ENTER. APPEND_OR_REPLACE starts
        // a fresh chain if a terminal failure cancelled the previous one.
        val workManager = workManagerProvider.getWorkManager()
        if (workManager != null) {
            // Await persistence so the BroadcastReceiver doesn't finish() before WM commits the work spec.
            workManager.enqueueUniqueWork(
                ORDERED_GEOFENCE_DELIVERY_QUEUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest
            ).await()
        } else {
            asyncTracker.trackEvent(entry)
        }
    }

    private companion object {
        const val WORK_MANAGER_TAG_CIO = "cio-requests"
        const val WORK_MANAGER_TAG_GEOFENCE = "cio-geofence"
    }
}

internal class GeofenceEventWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // WorkManager may restart this worker in a cold process where the host app hasn't
        // initialized the SDK yet; without this, SDKComponent.android() would throw.
        SDKComponent.setupAndroidComponent(context = applicationContext)
        val logger = SDKComponent.geofenceLogger
        // Each node drains the oldest row, not the one that scheduled it, so order survives retries,
        // foreground handoff and process death.
        val store = SDKComponent.android().pendingGeofenceDeliveryStore
        var sawEntry = false
        while (true) {
            val queued = store.loadAllOrNull() ?: run {
                // Unreadable is not empty, so not success. failure() is safe here, unlike below:
                // every dependent would fail the same read, and the rows stay on disk.
                val willRetry = runAttemptCount < GeofenceConstants.MAX_WORKER_RUN_ATTEMPTS
                logger.logEventWorkerQueueUnreadable(runAttemptCount, willRetry)
                return if (willRetry) Result.retry() else Result.failure()
            }
            val entry = queued.firstOrNull() ?: run {
                // Logged only when this wake found nothing; draining the queue empty is normal.
                if (!sawEntry) logger.logEventWorkerEntryMissing()
                return Result.success()
            }
            sawEntry = true

            // Anonymous rows shouldn't exist and can't gain a userId later, so drop the
            // row rather than block the ordered queue. A failed removal retries with backoff.
            if (entry.userId.isNullOrEmpty()) {
                logger.logEventDeliveryDeferredAnonymous(entry.geofenceId, entry.transition.name)
                if (store.remove(entry.key)) continue
                return Result.retry()
            }

            // At-least-once: the row stays until the send is confirmed. Duplicates (flush overlap, a
            // retry after an ambiguous success) are deduped backend-side on transitionId.
            when (
                val outcome = store.sendRemoveOnSuccess(entry, ::isRetryableDeliveryFailure) {
                    SDKComponent.android().geofenceEventTracker.trackEvent(entry)
                }
            ) {
                PendingDeliveryResult.AlreadyClaimed ->
                    logger.logEventDeliverySkippedAlreadyDelivered(
                        entry.geofenceId,
                        entry.transition.name
                    )
                PendingDeliveryResult.Delivered ->
                    logger.logEventDelivered(entry.geofenceId, entry.transition.name)
                PendingDeliveryResult.DeliveredNotRemoved -> {
                    // Sent but still on disk. Looping would resend the same head row without bound;
                    // a backed-off retry costs at most one deduped duplicate.
                    logger.logEventDeliveredButNotRemoved(entry.geofenceId, entry.transition.name)
                    return Result.retry()
                }
                is PendingDeliveryResult.Retryable -> {
                    logger.logEventDeliveryRetryable(
                        entry.geofenceId,
                        entry.transition.name,
                        outcome.cause?.message
                    )
                    return Result.retry()
                }
                is PendingDeliveryResult.Failed -> {
                    // Only a terminal HTTP status is known-permanent: drop that row so it doesn't
                    // block the ordered queue. Any other failure keeps the row and retries.
                    val cause = outcome.cause
                    val dropped = cause is HttpRequestFailure && !cause.isRetryable && store.remove(entry.key)
                    logger.logEventDeliveryFailed(
                        entry.geofenceId,
                        entry.transition.name,
                        cause?.message,
                        willRetry = !dropped
                    )
                    if (dropped) {
                        continue
                    }
                    // Not failure(): it cancels every dependent in the chain. Not success(): if this
                    // is the last node, nothing would come back for the row.
                    return Result.retry()
                }
            }
        }
    }
}

/**
 * Every non-2xx is an [HttpRequestFailure], which is an [IOException], so the default
 * `it is IOException` predicate would retry a 400 or 401 forever.
 */
internal fun isRetryableDeliveryFailure(cause: Throwable?): Boolean = when (cause) {
    is HttpRequestFailure -> cause.isRetryable
    is IOException -> true
    else -> false
}
