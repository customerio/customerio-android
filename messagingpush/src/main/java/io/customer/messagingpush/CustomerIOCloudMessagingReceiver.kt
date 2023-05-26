package io.customer.messagingpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.cloudmessaging.CloudMessagingReceiver
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.extensions.getSDKInstanceOrNull
import io.customer.messagingpush.processor.PushMessageProcessor

/**
 * Broadcast receiver for listening to push events from GoogleCloudMessaging (GCM).
 * The receiver listens to message broadcast emitted by Google Cloud APIs directly.
 *
 * The receiver inherits [BroadcastReceiver] instead of [CloudMessagingReceiver]
 * so we can track/process notifications using raw data and without relying strongly
 * on message/bundle structure used by [CloudMessagingReceiver] as we only need to
 * process notifications from Customer.io.
 *
 * @see [PushMessageProcessor.processGCMMessageIntent] to understand the goals of this
 * class better
 */
class CustomerIOCloudMessagingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        // Ignore event if no data was received in extras
        if (extras == null || extras.isEmpty) return
        // If CustomerIO instance isn't initialized, we cannot process the notification
        val sdkInstance = context.getSDKInstanceOrNull() ?: return

        sdkInstance.diGraph.pushMessageProcessor.processGCMMessageIntent(intent = intent)
    }
}
