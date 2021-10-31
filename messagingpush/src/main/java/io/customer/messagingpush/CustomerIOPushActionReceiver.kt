package io.customer.messagingpush

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.CustomerIOFirebaseMessagingService.Companion.DELIVERY_ID
import io.customer.messagingpush.CustomerIOFirebaseMessagingService.Companion.DELIVERY_TOKEN
import io.customer.messagingpush.CustomerIOFirebaseMessagingService.Companion.REQUEST_CODE
import io.customer.sdk.CustomerIO
import io.customer.sdk.data.request.MetricEvent

class CustomerIOPushActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.customer.messagingpush.PUSH_ACTION"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        // Dismiss the notification
        val requestCode = intent!!.getIntExtra(REQUEST_CODE, 0)
        val mNotificationManager =
            context!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.cancel(requestCode)

        val deliveryId = intent.getStringExtra(DELIVERY_ID)
        val deliveryToken = intent.getStringExtra(DELIVERY_TOKEN)

        if (deliveryId != null && deliveryToken != null) {
            CustomerIO.instance().trackMetric(deliveryId, MetricEvent.opened, deliveryToken)
                .enqueue()
        }

    }
}
