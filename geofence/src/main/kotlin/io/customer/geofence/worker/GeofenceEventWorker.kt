package io.customer.geofence.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
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
import io.customer.sdk.core.network.HttpRequestFailure
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.data.store.PendingDeliveryResult
import io.customer.sdk.data.store.sendRemoveOnSuccess
import java.io.IOException

private const val ORDERED_GEOFENCE_DELIVERY_QUEUE = "cio-geofence-delivery-queue"

/**
 * Schedules a [GeofenceEventWorker] for guaranteed delivery of a geofence transition event.
 * Falls back to in-process async HTTP if WorkManager is unavailable (does not survive death).
 */
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

        // Every transition joins one durable continuation chain. This prevents a later EXIT from
        // overtaking an earlier ENTER when WorkManager has multiple workers eligible at once.
        // APPEND_OR_REPLACE also creates a fresh chain if a prior terminal failure cancelled it.
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
        // Every node drains the oldest durable row, not the row that happened to schedule it. This
        // preserves transition order even after retries, foreground handoff, or process death.
        val store = SDKComponent.android().pendingGeofenceDeliveryStore
        while (true) {
            val entry = store.loadAll().firstOrNull() ?: return Result.success()

            // Shouldn't happen — the receiver drops anonymous transitions before persisting. It is
            // also unrecoverable: the userId was snapshotted at queue time and no later attempt can
            // supply one, so the row is undeliverable forever. Leaving it in place would strand the
            // head of an ordered queue and with it every transition queued behind it, so drop it and
            // carry on. A failed removal falls through to a backed-off retry rather than spinning.
            if (entry.userId.isNullOrEmpty()) {
                logger.logEventDeliveryDeferredAnonymous(entry.geofenceId, entry.transition.name)
                if (store.remove(entry.key)) continue
                return Result.retry()
            }

            // At-least-once delivery: keep the row until the send is confirmed, so a process death
            // mid-request leaves it for a WorkManager retry or the foreground flush rather than
            // dropping it. The duplicate this can produce (overlap with the flush, or a retry after
            // an ambiguous success) is deduped backend-side via the stable transitionId.
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
                    // Sent, but the row that proves it is still on disk. Continuing the loop would
                    // re-read this same head entry and resend it, and again, without bound. Hand the
                    // problem to WorkManager's backoff: at worst one delayed duplicate, deduped
                    // backend-side on transitionId, instead of an unbounded burst of them.
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
                    logger.logEventDeliveryFailed(
                        entry.geofenceId,
                        entry.transition.name,
                        outcome.cause?.message
                    )
                    // A refused payload is refused identically next time, so retrying only holds the
                    // head of an ordered queue and every transition behind it. Same disposal as the
                    // anonymous row above: drop it and carry on. Narrow on purpose — only a terminal
                    // HTTP status is known-permanent. Anything else (a bad state, a serialization
                    // slip) may well succeed on the next attempt, so it keeps the row and retries.
                    val cause = outcome.cause
                    if (cause is HttpRequestFailure && !cause.isRetryable && store.remove(entry.key)) {
                        continue
                    }
                    // Still not Result.failure(): a terminal node cancels every existing dependent in
                    // a WorkManager chain, discarding the transitions queued behind this one. Not
                    // Result.success() either — this node may be the last in the chain, and then
                    // nothing would come back for the row, stranding the head of an ordered queue.
                    return Result.retry()
                }
            }
        }
    }
}

/**
 * A delivery failure worth attempting again.
 *
 * Every non-2xx arrives as an [HttpRequestFailure], so without this the default `it is IOException`
 * predicate classifies a 400 or 401 as retryable and the row is re-sent forever. Transport failures
 * stay retryable; anything that is not an [IOException] at all is a bug rather than a network
 * condition, and repeating it would not help.
 */
internal fun isRetryableDeliveryFailure(cause: Throwable?): Boolean = when (cause) {
    is HttpRequestFailure -> cause.isRetryable
    is IOException -> true
    else -> false
}
