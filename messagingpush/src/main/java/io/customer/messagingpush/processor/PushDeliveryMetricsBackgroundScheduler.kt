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
import io.customer.sdk.data.store.PendingDeliveryResult
import io.customer.sdk.data.store.claimSendRestore
import io.customer.sdk.events.Metric

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
        val entry = PendingPushDeliveryMetric(deliveryId = deliveryId, token = deliveryToken)

        // Shared exactly-once decision: atomically claim before sending (a lost
        // claim means the foreground handoff already delivered this metric via
        // the analytics pipeline, so sending here would double-count it), and
        // restore the entry on failure so a retry or the handoff can deliver it.
        return when (
            val outcome = store.claimSendRestore(entry) {
                SDKComponent.pushDeliveryTracker.trackMetric(
                    event = Metric.Delivered.name,
                    deliveryId = deliveryId,
                    token = deliveryToken
                )
            }
        ) {
            PendingDeliveryResult.AlreadyClaimed -> {
                logger.logWorkerSkippedAlreadyDelivered(deliveryId)
                Result.success()
            }
            PendingDeliveryResult.Delivered -> {
                logger.logWorkerSuccessRemoved(deliveryId)
                Result.success()
            }
            is PendingDeliveryResult.Retryable -> {
                logger.logWorkerRetry(deliveryId, outcome.cause)
                Result.retry()
            }
            is PendingDeliveryResult.Failed -> {
                logger.logWorkerFailure(deliveryId, outcome.cause)
                Result.failure()
            }
        }
    }
}
