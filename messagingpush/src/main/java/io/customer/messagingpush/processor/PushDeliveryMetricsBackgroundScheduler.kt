package io.customer.messagingpush.processor

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import io.customer.messagingpush.di.pushDeliveryTracker
import io.customer.messagingpush.util.WorkManagerProvider
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.events.Metric

private const val DELIVERY_ID = "delivery-id"
private const val DELIVERY_TOKEN = "delivery-token"

internal class PushDeliveryMetricsBackgroundScheduler(
    private val workManagerProvider: WorkManagerProvider
) {

    fun scheduleDeliveredPushMetricsReceipt(deliveryId: String, deliveryToken: String) {
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
            .build()

        workManagerProvider.getWorkManager()?.enqueueUniqueWork(deliveryId, ExistingWorkPolicy.KEEP, workRequest)
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
            return Result.success()
        }

        val result = SDKComponent.pushDeliveryTracker.trackMetric(
            event = Metric.Delivered.name,
            deliveryId = deliveryId,
            token = deliveryToken
        )

        return when {
            result.isSuccess -> Result.success()
            else -> Result.failure()
        }
    }
}
