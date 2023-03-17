package io.customer.messagingpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.RemoteMessage

class CustomerIOFirebaseMessagingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val remoteMessage = RemoteMessage(intent.extras)
        CustomerIOFirebaseMessagingService.onMessageReceived(context, remoteMessage)
    }
}
