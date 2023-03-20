package io.customer.messagingpush.processor

import com.google.firebase.messaging.RemoteMessage
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.Logger

/**
 * Helper file to hold all message processing tasks at a single place.
 */
@InternalCustomerIOApi
class PushMessageProcessor(
    private val logger: Logger,
    private val dateUtil: DateUtil
) {
    private val messageProcessingTimestamp = mutableMapOf<String, Long>()

    /**
     * Should be called whenever push notification is received. The method is
     * responsible for making sure that a CIO notification is processed only one
     * time even if the listeners are invoked multiple times.
     *
     * @param remoteMessage message received from FCM.
     * @param handler callback to process the message, the call is ignored if the
     * notification was processed previously.
     * @return true if the notification was processed previously, else returns the
     * value received from [handler].
     */
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
