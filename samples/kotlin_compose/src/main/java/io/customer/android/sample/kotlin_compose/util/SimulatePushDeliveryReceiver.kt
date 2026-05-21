package io.customer.android.sample.kotlin_compose.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.CustomerIOFirebaseMessagingService

/**
 * Dev-only broadcast receiver that simulates an FCM delivered-metric callback
 * without requiring a real Firebase Cloud Messaging round-trip. Constructs a
 * synthetic [RemoteMessage] carrying the Customer.io delivery extras and feeds
 * it through the public SDK entry point so the production push-delivery code
 * path runs exactly as it would for a real FCM message.
 *
 * Usage:
 *   adb shell am broadcast \
 *     -a io.customer.sample.SIMULATE_PUSH_DELIVERY \
 *     --es deliveryId d1 --es token tok1 \
 *     -p io.customer.android.sample.kotlin_compose
 */
class SimulatePushDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getStringExtra(EXTRA_DELIVERY_ID)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (deliveryId.isNullOrBlank() || token.isNullOrBlank()) {
            Log.w(TAG, "Missing deliveryId/token in simulate-push broadcast")
            return
        }
        Log.i(TAG, "simulating push delivery key=$deliveryId")
        val remoteMessage = RemoteMessage.Builder("simulator@simulated")
            .addData(KEY_CIO_DELIVERY_ID, deliveryId)
            .addData(KEY_CIO_DELIVERY_TOKEN, token)
            .addData("title", "simulated push")
            .addData("body", "simulated body for $deliveryId")
            .build()
        CustomerIOFirebaseMessagingService.onMessageReceived(
            context = context.applicationContext,
            remoteMessage = remoteMessage,
            handleNotificationTrigger = false
        )
    }

    companion object {
        const val ACTION = "io.customer.sample.SIMULATE_PUSH_DELIVERY"
        const val EXTRA_DELIVERY_ID = "deliveryId"
        const val EXTRA_TOKEN = "token"
        private const val KEY_CIO_DELIVERY_ID = "CIO-Delivery-ID"
        private const val KEY_CIO_DELIVERY_TOKEN = "CIO-Delivery-Token"
        private const val TAG = "CIO-SimulatePush"
    }
}
