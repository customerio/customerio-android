package io.customer.messagingpush.processor

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import io.customer.messagingpush.AsyncPushDeliveryTracker
import io.customer.messagingpush.di.pendingPushDeliveryStore
import io.customer.messagingpush.di.pushDeliveryTracker
import io.customer.messagingpush.di.pushLogger
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.events.Metric
import java.io.IOException

private const val DELIVERY_ID = "delivery-id"
private const val DELIVERY_TOKEN = "delivery-token"

internal class PushDeliveryMetricsBackgroundScheduler(
    private val workManagerProvider: CustomerIOWorkManagerProvider,
    private val asyncPushDeliveryTracker: AsyncPushDeliveryTracker
) {

    companion object {
        internal const val WORK_MANAGER_TAG_CIO = "cio-requests"
        internal const val WORK_MANAGER_TAG_PUSH_DELIVERY = "cio-push-delivery"
    }

    fun scheduleDeliveredPushMetricsReceipt(
        deliveryId: String,
        deliveryToken: String
    ) {
        val input = Data.Builder()
            .putString(DELIVERY_ID, deliveryId)
            .putString(DELIVERY_TOKEN, deliveryToken)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PushDeliveryMetricsWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(input)
            .addTag(WORK_MANAGER_TAG_CIO)
            .addTag(WORK_MANAGER_TAG_PUSH_DELIVERY)
            .build()

        val logger = SDKComponent.pushLogger
        val workManager = workManagerProvider.getWorkManager()
        if (workManager != null) {
            workManager.enqueueUniqueWork(deliveryId, ExistingWorkPolicy.KEEP, workRequest)
            logger.logWorkManagerEnqueued(deliveryId)
        } else {
            logger.logWorkManagerUnavailableAsyncFallback(deliveryId)
            asyncPushDeliveryTracker.trackMetric(
                token = deliveryToken,
                event = Metric.Delivered.name,
                deliveryId = deliveryId
            )
        }
    }
}

internal class PushDeliveryMetricsWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val deliveryId = inputData.getString(DELIVERY_ID)
        val deliveryToken = inputData.getString(DELIVERY_TOKEN)

        if (deliveryId.isNullOrEmpty() || deliveryToken.isNullOrEmpty()) {
            // Missing delivery data, prevent task from being retried
            return Result.failure()
        }

        val logger = SDKComponent.pushLogger
        val store = SDKComponent.pendingPushDeliveryStore

        // Atomically claim the entry before sending. If it's already gone, the
        // foreground handoff (which also claims before publishing) already
        // delivered this metric via the analytics pipeline, so sending here
        // would double-count it. This guards the race when WorkManager restores
        // a persisted job after process death and runs it alongside the handoff.
        if (!store.claim(deliveryId)) {
            logger.logWorkerSkippedAlreadyDelivered(deliveryId)
            return Result.success()
        }

        val result = SDKComponent.pushDeliveryTracker.trackMetric(
            event = Metric.Delivered.name,
            deliveryId = deliveryId,
            token = deliveryToken
        )

        return when {
            result.isSuccess -> {
                // Entry was already removed by claim() above; just log success.
                logger.logWorkerSuccessRemoved(deliveryId)
                Result.success()
            }
            result.exceptionOrNull() is IOException -> {
                // Transient failure: restore the claim so a WorkManager retry —
                // or the foreground handoff — can deliver it later.
                store.append(PendingPushDeliveryMetric(deliveryId = deliveryId, token = deliveryToken))
                logger.logWorkerRetry(deliveryId, result.exceptionOrNull())
                Result.retry()
            }
            else -> {
                store.append(PendingPushDeliveryMetric(deliveryId = deliveryId, token = deliveryToken))
                logger.logWorkerFailure(deliveryId, result.exceptionOrNull())
                Result.failure()
            }
        }
    }
}
