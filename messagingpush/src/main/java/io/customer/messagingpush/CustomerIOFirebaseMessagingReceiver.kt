package io.customer.messagingpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.RemoteMessage

/**
 * BroadcastReceiver to listen to push messages sent through Firebase. The receiver works as workaround
 * and is notified directly without registering separate service for com.google.firebase.MESSAGING_EVENT.
 */
class CustomerIOFirebaseMessagingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CustomerIOFirebaseMessagingService.onMessageReceived(context, RemoteMessage(intent.extras))
    }
}
