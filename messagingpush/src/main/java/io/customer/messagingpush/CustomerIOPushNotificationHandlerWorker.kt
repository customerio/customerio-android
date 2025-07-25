package io.customer.messagingpush

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.util.NotificationChannelCreator
import io.customer.sdk.core.di.SDKComponent

class CustomerIOPushNotificationHandlerWorker(
    private val context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    companion object {
        private const val INPUT_KEY_TIMESTAMP = "rm_notification_timestamp"

        const val REMOTE_NOTIFICATION_TITLE_KEY = "rm_notification_title"
        const val REMOTE_NOTIFICATION_BODY_KEY = "rm_notification_body"
        const val REMOTE_NOTIFICATION_ICON_KEY = "rm_notification_icon"
        const val REMOTE_NOTIFICATION_IMAGE_KEY = "rm_notification_image"
        const val REMOTE_NOTIFICATION_COLOR_KEY = "rm_notification_color"
        const val REMOTE_NOTIFICATION_TRIGGER_KEY = "rm_notification_trigger"

        fun buildWorkRequest(remoteMessage: RemoteMessage, handleNotificationTrigger: Boolean): OneTimeWorkRequest {
            val remoteNotification = remoteMessage.notification
            val inputData = Data.Builder().apply {
                remoteMessage.data.forEach { (key, value) -> putString(key, value) }
                // notification values
                putString(REMOTE_NOTIFICATION_TITLE_KEY, remoteNotification?.title)
                putString(REMOTE_NOTIFICATION_BODY_KEY, remoteNotification?.body)
                putString(REMOTE_NOTIFICATION_ICON_KEY, remoteNotification?.icon)
                putString(REMOTE_NOTIFICATION_IMAGE_KEY, remoteNotification?.imageUrl?.toString())
                putString(REMOTE_NOTIFICATION_COLOR_KEY, remoteNotification?.color)
                putBoolean(REMOTE_NOTIFICATION_TRIGGER_KEY, handleNotificationTrigger)
                putLong(INPUT_KEY_TIMESTAMP, System.currentTimeMillis())
            }.build()

            return OneTimeWorkRequestBuilder<CustomerIOPushNotificationHandlerWorker>()
                .setInputData(inputData)
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val workerDataMap = inputData.keyValueMap
            .filterValues { it is String }
            .mapValues { it.value as String }
        val dataMap = mutableMapOf(
            *workerDataMap.toList().toTypedArray()
        ).apply {
            remove(REMOTE_NOTIFICATION_TITLE_KEY)
            remove(REMOTE_NOTIFICATION_BODY_KEY)
            remove(REMOTE_NOTIFICATION_ICON_KEY)
            remove(REMOTE_NOTIFICATION_IMAGE_KEY)
            remove(REMOTE_NOTIFICATION_COLOR_KEY)
        }
        val notificationDataMap = mutableMapOf<String, String?>().apply {
            put(REMOTE_NOTIFICATION_TITLE_KEY, workerDataMap[REMOTE_NOTIFICATION_TITLE_KEY])
            put(REMOTE_NOTIFICATION_BODY_KEY, workerDataMap[REMOTE_NOTIFICATION_BODY_KEY])
            put(REMOTE_NOTIFICATION_ICON_KEY, workerDataMap[REMOTE_NOTIFICATION_ICON_KEY])
            put(REMOTE_NOTIFICATION_IMAGE_KEY, workerDataMap[REMOTE_NOTIFICATION_IMAGE_KEY])
            put(REMOTE_NOTIFICATION_COLOR_KEY, workerDataMap[REMOTE_NOTIFICATION_COLOR_KEY])
        }

        // val remoteMessage = RemoteMessage(dataMap)

        val handler = CustomerIOPushNotificationHandler(
            pushMessageProcessor = SDKComponent.pushMessageProcessor,
            remoteMessageData = dataMap,
            remoteMessageNotificationData = notificationDataMap,
            notificationChannelCreator = NotificationChannelCreator()
        )

        return try {
            val handleNotificationTrigger = (inputData.keyValueMap[REMOTE_NOTIFICATION_TRIGGER_KEY] as? Boolean) ?: true
            val handled = handler.handleMessage(context, handleNotificationTrigger)
            if (handled) Result.success() else Result.failure()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
