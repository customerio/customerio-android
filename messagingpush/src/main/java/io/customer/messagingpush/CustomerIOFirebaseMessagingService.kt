package io.customer.messagingpush

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.sdk.CustomerIO

class CustomerIOFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        /**
         * Handles receiving an incoming push notification.
         *
         * Call this from a custom [FirebaseMessagingService] to pass push messages to
         * CustomerIo SDK for tracking and rendering
         * @param remoteMessage Remote message received from Firebase in
         * [FirebaseMessagingService.onMessageReceived]
         * @param handleNotificationTrigger indicating if the local notification should be triggered
         * @return Boolean indicating whether this will be handled by CustomerIo
         */
        @JvmOverloads
        fun onMessageReceived(
            context: Context,
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            return handleMessageReceived(context, remoteMessage, handleNotificationTrigger)
        }

        /**
         * Handles new or refreshed token
         * Call this from [FirebaseMessagingService] to register the new device token
         *
         * @param token new or refreshed token
         */
        @JvmOverloads
        fun onNewToken(
            context: Context,
            token: String
        ) {
            handleNewToken(context, token)
        }

        private fun handleNewToken(context: Context, token: String) {
            CustomerIO.instanceOrNull(context)?.registerDeviceToken(deviceToken = token)
        }

        private fun handleMessageReceived(
            context: Context,
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            val handler =
                CustomerIOPushNotificationHandler(context = context, remoteMessage = remoteMessage)
            return handler.handleMessage(context, handleNotificationTrigger)
        }
    }

    override fun onNewToken(token: String) {
        handleNewToken(context = this, token = token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        handleMessageReceived(this, remoteMessage)
    }
}
