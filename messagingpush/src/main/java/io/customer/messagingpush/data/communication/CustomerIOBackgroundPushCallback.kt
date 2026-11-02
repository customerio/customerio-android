package io.customer.messagingpush.data.communication

import android.content.Context
import io.customer.messagingpush.data.model.CustomerIOBackgroundPushPayload

/**
 * Receives background data without posting a notification or tracking an opened event.
 * Runs on the calling receive thread. Keep work short and schedule longer work with
 * WorkManager. Delivery is not guaranteed and can repeat; use the delivery ID to make
 * application work idempotent. Receipt deduplication does not deduplicate this callback.
 */
fun interface CustomerIOBackgroundPushCallback {
    fun onBackgroundPushReceived(context: Context, payload: CustomerIOBackgroundPushPayload)
}
