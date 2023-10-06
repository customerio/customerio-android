package io.customer.messagingpush.processor

import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.RemoteMessage
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messagingpush.processor.PushMessageProcessor.Companion.RECENT_MESSAGES_MAX_SIZE
import java.util.concurrent.LinkedBlockingDeque

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

    /**
     * Executes the necessary actions when a notification is clicked by the user.
     *
     * This method performs the following tasks:
     * 1. Tracks 'opened' metrics for the notification.
     * 2. Resolves the deep link, if available in the notification payload, and navigates to the corresponding screen.
     * 3. If no deep link is provided, opens the default launcher screen.
     *
     * This method may only be called from `onCreate` or `onNewIntent` methods of notification handler activity.
     *
     * @param activityContext context should be from activity as this will be used for launching activity
     * @param intent intent received by the activity
     */
    fun processNotificationClick(activityContext: Context, intent: Intent)

    companion object {
        // Count of messages stored in memory
        const val RECENT_MESSAGES_MAX_SIZE = 10

        // Queue to store recent messages received. The most recent message should be the
        // first element while the oldest being the last.
        val recentMessagesQueue: LinkedBlockingDeque<String> =
            LinkedBlockingDeque(RECENT_MESSAGES_MAX_SIZE)
    }
}
