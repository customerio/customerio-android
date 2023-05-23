package io.customer.messagingpush.processor

import android.content.Intent
import com.google.firebase.messaging.RemoteMessage
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.processor.PushMessageProcessor.Companion.RECENT_MESSAGES_MAX_SIZE

/**
 * Push notification processor class that is responsible for processing notification
 * received from Customer.io and achieve the following goals:
 * - Track delivered metrics from GCM receiver intent
 * - Track delivered metrics from Firebase message receiver
 * - Avoid duplication in delivered metrics by keeping track of last N messages (where N is [RECENT_MESSAGES_MAX_SIZE])
 */
@InternalCustomerIOApi
interface PushMessageProcessor {
    /**
     * Process intent received by GCM receiver. The receiver can pass the same
     * intent as received where processor class will be responsible for parsing,
     * identifying and tracking delivered metrics correctly.
     *
     * The method only tracks delivered events for all types of notifications
     * currently.
     *
     * @param intent received by GCM receiver
     */
    fun processGCMMessageIntent(intent: Intent)

    /**
     * Process message received by Firebase messaging receiver. The receiver should
     * pass delivery parameters extracted from [RemoteMessage] received and processor
     * class will then be responsible for identifying and tracking delivered metrics
     * correctly.
     *
     * As the name suggests, the purpose of this method is only to track delivered
     * events for notifications (mainly rich push as Firebase message is not
     * delivered for simple push when app is in background).
     *
     * @see [Firebase Docs][https://firebase.google.com/docs/cloud-messaging/android/receive#handling_messages]
     * for details on FCM message behavior
     *
     * @param deliveryId received in push payload
     * @param deliveryToken received in push payload
     */
    fun processRemoteMessageDeliveredMetrics(deliveryId: String, deliveryToken: String)

    companion object {
        // Count of messages stored in memory
        const val RECENT_MESSAGES_MAX_SIZE = 10

        // Queue to store recent messages received. The most recent message should be the
        // first element while the oldest being the last.
        val recentMessagesQueue: ArrayDeque<String> = ArrayDeque(RECENT_MESSAGES_MAX_SIZE)
    }
}
