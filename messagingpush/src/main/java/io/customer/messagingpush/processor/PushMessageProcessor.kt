package io.customer.messagingpush.processor

import android.content.Intent
import io.customer.base.internal.InternalCustomerIOApi

@InternalCustomerIOApi
interface PushMessageProcessor {
    fun processGCMMessageIntent(intent: Intent)
    fun processRemoteMessageDeliveredMetrics(deliveryId: String, deliveryToken: String)

    companion object {
        const val RECENT_MESSAGES_MAX_SIZE = 10

        val recentMessagesQueue: ArrayDeque<String> = ArrayDeque(RECENT_MESSAGES_MAX_SIZE)
    }
}
