package io.customer.messagingpush

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.sdk.CustomerIO
import io.customer.sdk.di.CustomerIOComponent

class CustomerIOFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FirebaseMessaging:"

        private val diGraph: CustomerIOComponent
            get() = CustomerIO.instance().diGraph

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
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            return handleMessageReceived(remoteMessage, handleNotificationTrigger)
        }

        /**
         * Handles new or refreshed token
         * Call this from [FirebaseMessagingService] to register the new device token
         *
         * @param token new or refreshed token
         */
        @JvmOverloads
        fun onNewToken(
            token: String
        ) {
            handleNewToken(token)
        }

        private fun handleNewToken(token: String) {
            CustomerIO.instanceOrNull()?.registerDeviceToken(deviceToken = token)
        }

        private fun handleMessageReceived(
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            val handler = CustomerIOPushNotificationHandler(remoteMessage = remoteMessage)
            return handler.handleMessage(diGraph.context, handleNotificationTrigger)
        }
    }

    override fun onNewToken(token: String) {
        handleNewToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        handleMessageReceived(remoteMessage)
    }
}
