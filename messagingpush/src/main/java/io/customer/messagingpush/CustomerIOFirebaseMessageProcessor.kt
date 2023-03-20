package io.customer.messagingpush

import com.google.firebase.messaging.RemoteMessage
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.Logger

/**
 * Helper file to hold all message processing tasks at a single place.
 */
@InternalCustomerIOApi
interface CustomerIOFirebaseMessageProcessor {
    /**
     * Should be called whenever push notification is received. The method is
     * responsible for making sure that a CIO notification is processed only one
     * time even if the listeners are invoked multiple times.
     *
     * @param remoteMessage message received from FCM.
     * @param handler callback to process the message, the call is ignored if the
     * notification was processed previously.
     * @return true if the notification was processed previously, false if the
     * notification is not from Customer.io; else returns the value received from
     * [handler].
     */
    fun onMessageReceived(
        remoteMessage: RemoteMessage,
        handler: () -> Boolean
    ): Boolean
}

@InternalCustomerIOApi
internal class CustomerIOFirebaseMessageProcessorImpl(
    private val logger: Logger,
    private val dateUtil: DateUtil
) : CustomerIOFirebaseMessageProcessor {
    /**
     * Map to hold timestamp for when a message was process last time. This helps
     * deciding between whether the notification should be process or not.
     */
    private val messageProcessingTimestamp = mutableMapOf<String, Long>()

    override fun onMessageReceived(remoteMessage: RemoteMessage, handler: () -> Boolean): Boolean {
        val cioDeliveryToken = remoteMessage.data[PushTrackingUtil.DELIVERY_TOKEN_KEY]
        // Skip processing the notification if there isn't any Customer.io delivery token
        if (cioDeliveryToken.isNullOrBlank()) {
            logger.info("Not a CIO push notification, skipping processing")
            return false
        }

        val isHandled: Boolean
        synchronized(this) {
            val messageLastProcessedAt = messageProcessingTimestamp[cioDeliveryToken] ?: 0
            if (dateUtil.nowUnixTimestamp - messageLastProcessedAt > NOTIFICATION_PROCESSING_DEBOUNCE_TIME) {
                isHandled = handler()
                messageProcessingTimestamp[cioDeliveryToken] = dateUtil.nowUnixTimestamp
            } else {
                logger.info("CIO push notification already processed with token $cioDeliveryToken")
                isHandled = true
            }
        }
        return isHandled
    }

    companion object {
        /**
         * The minimum time gap between processing two similar notifications.
         * Any notification received in between this should be skipped.
         */
        private const val NOTIFICATION_PROCESSING_DEBOUNCE_TIME = 3_000L
    }
}
