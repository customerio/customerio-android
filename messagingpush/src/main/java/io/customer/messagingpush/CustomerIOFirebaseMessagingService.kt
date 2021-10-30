package io.customer.messagingpush

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.CustomerIOPushActionReceiver.Companion.ACTION
import io.customer.sdk.CustomerIO
import io.customer.sdk.data.request.MetricEvent
import kotlin.math.abs

class CustomerIOFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        const val TAG = "FirebaseMessaging:"
        const val DELIVERY_ID = "CIO-Delivery-ID:"
        const val DELIVERY_TOKEN = "CIO-Delivery-Token"
        const val REQUEST_CODE = "requestCode"

        private const val CHANNEL_NAME = "CustomerIO Channel"

        /**
         * Handles receiving an incoming push notification from the intent.
         *
         * Call this from a custom [FirebaseMessagingService] to pass push messages to
         * CustomerIo SDK for tracking and rendering
         * @param remoteMessage Remote message received from Firebase in
         * [FirebaseMessagingService.onMessageReceived]
         * @param handleNotificationTrigger indicating if the local notification should be triggered
         * @return Boolean indicating whether this will be handled by CustomerIo
         */
        fun handleMessageReceived(
            context: Context,
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            // Check if message contains a data payload.
            // You can have data only notifications.
            if (remoteMessage.data.isEmpty()) {
                Log.d(TAG, "Message data payload: " + remoteMessage.data)
                return false
            }

            // Customer.io push notifications include data regarding the push
            // message in the data part of the payload which can be used to send
            // feedback into our system.
            val deliveryId = remoteMessage.data[DELIVERY_ID]
            val deliveryToken = remoteMessage.data[DELIVERY_TOKEN]

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

            // Check if message contains a notification payload.
            if (remoteMessage.notification != null && handleNotificationTrigger) {

                val pushContentIntent = Intent(ACTION)
                pushContentIntent.setClass(context, CustomerIOPushActionReceiver::class.java)
                pushContentIntent.putExtra(DELIVERY_ID, deliveryId)
                pushContentIntent.putExtra(DELIVERY_TOKEN, deliveryToken)

                handleNotification(
                    context,
                    remoteMessage.notification!!,
                    pushContentIntent
                )
            }

            return true
        }

        @SuppressLint("LaunchActivityFromNotification")
        private fun handleNotification(
            context: Context,
            notification: RemoteMessage.Notification,
            pushContentIntent: Intent
        ) {

            val requestCode = abs(System.currentTimeMillis().toInt())
            pushContentIntent.putExtra(REQUEST_CODE, requestCode)

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val notificationClickedIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                pushContentIntent,
                flags
            )

            val icon = context.applicationInfo.icon

            val channelId = context.packageName
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(icon)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setContentIntent(notificationClickedIntent)

            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Since android Oreo notification channel is needed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
            }

            notificationManager.notify(requestCode, notificationBuilder.build())
        }

    }

    override fun onNewToken(token: String) {
        try {
            CustomerIO.instance().registerDeviceToken(deviceToken = token).enqueue()
        } catch (exception: IllegalStateException) {
            Log.e(TAG, "Error while handling token: ${exception.message}")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        handleMessageReceived(this, remoteMessage)
    }
}
