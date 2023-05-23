package io.customer.messagingpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.extensions.getSDKInstanceOrNull

class CustomerIOFirebaseMessagingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        // Ignore event if no data was received in extras
        if (extras == null || extras.isEmpty) return
        // If CustomerIO instance isn't initialized, we cannot process the notification
        val sdkInstance = context.getSDKInstanceOrNull() ?: return

        sdkInstance.diGraph.pushMessageProcessor.processGCMMessageIntent(intent = intent)
    }
}
