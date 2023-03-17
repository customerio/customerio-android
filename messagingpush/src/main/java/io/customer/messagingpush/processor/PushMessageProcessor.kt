package io.customer.messagingpush.processor

import com.google.firebase.messaging.RemoteMessage
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.Logger

@InternalCustomerIOApi
class PushMessageProcessor(
    private val logger: Logger,
    private val dateUtil: DateUtil
) {
    private val messageProcessingTimestamp = mutableMapOf<String, Long>()

    fun onPushReceived(
        remoteMessage: RemoteMessage,
        handler: () -> Boolean
    ): Boolean {
        val cioDeliveryToken =
            remoteMessage.data[PushTrackingUtil.DELIVERY_TOKEN_KEY] ?: return false
        val isHandled: Boolean
        synchronized(this) {
            val messageLastProcessedAt = messageProcessingTimestamp[cioDeliveryToken] ?: 0
            if (dateUtil.nowUnixTimestamp - messageLastProcessedAt > NOTIFICATION_PROCESSING_DEBOUNCE_TIME) {
                isHandled = handler()
                messageProcessingTimestamp[cioDeliveryToken] = dateUtil.nowUnixTimestamp
            } else {
                isHandled = true
            }
        }
        return isHandled
    }

    companion object {
        private const val NOTIFICATION_PROCESSING_DEBOUNCE_TIME = 3_000L
    }
}
