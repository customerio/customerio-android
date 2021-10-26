package io.customer.messagingpush

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.sdk.CustomerIO
import io.customer.sdk.data.request.MetricEvent

class CustomerIoFirebaseMessagingService : FirebaseMessagingService() {
    private companion object {
        private const val TAG = "FirebaseMessaging:"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        try {
            CustomerIO.instance().registerDeviceToken(deviceToken = token).enqueue()
        } catch (exception: IllegalStateException) {
            Log.e(TAG, "Error while handling token: ${exception.message}")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Check if message contains a data payload.
        // You can have data only notifications.
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Message data payload: " + remoteMessage.data)

            // Customer.io push notifications include data regarding the push
            // message in the data part of the payload which can be used to send
            // feedback into our system.
            val deliveryId = remoteMessage.data["CIO-Delivery-ID"]
            val deliveryToken = remoteMessage.data["CIO-Delivery-Token"]

            if (deliveryId != null && deliveryToken != null) {
                try {
                    CustomerIO.instance().trackMetric(
                        deliveryID = deliveryId,
                        deviceToken = deliveryToken,
                        event = MetricEvent.delivered
                    ).enqueue()
                } catch (exception: IllegalStateException) {
                    Log.e(TAG, "Error while handling message: ${exception.message}")
                }

            }
        }

        // Check if message contains a notification payload.
        if (remoteMessage.notification != null) {
            handleNotification(remoteMessage.notification!!)
        }

    }

    private fun handleNotification(notification: RemoteMessage.Notification) {
        Log.d(TAG, "Message Notification Body: " + notification.body)
    }
}
