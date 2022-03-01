package io.customer.messagingpush

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.base.comunication.Action
import io.customer.sdk.CustomerIO
import io.customer.sdk.extensions.getErrorResult

/**
 * Uses the singleton instance of [MessagingPush]/[CustomerIO]. If you want to use a different site id/api key combination,
 * create your own [FirebaseMessagingService] subclass and call [MessagingPush] functions manually.
 */
class CustomerIOFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FirebaseMessaging:"

        /**
         * Handles receiving an incoming push notification.
         *
         * Call this from a custom [FirebaseMessagingService] to pass push messages to
         * CustomerIo SDK for tracking and rendering
         * @param remoteMessage Remote message received from Firebase in
         * [FirebaseMessagingService.onMessageReceived]
         * @param handleNotificationTrigger indicating if the local notification should be triggered
         * @param errorCallback callback containing any error occurred
         * @return Boolean indicating whether this will be handled by CustomerIo
         */
        fun onMessageReceived(
            context: Context,
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true,
            errorCallback: Action.Callback<Unit> = Action.Callback { }
        ): Boolean {
            return try {
                handleMessageReceived(context, remoteMessage, handleNotificationTrigger)
            } catch (e: Exception) {
                errorCallback.onResult(e.getErrorResult())
                false
            }
        }

        /**
         * Handles new or refreshed token
         * Call this from [FirebaseMessagingService] to register the new device token
         *
         * @param token new or refreshed token
         * @param errorCallback callback containing any error occurred
         */
        fun onNewToken(
            token: String,
            errorCallback: Action.Callback<Unit> = Action.Callback { }
        ) {
            handleNewToken(token, errorCallback)
        }

        private fun handleNewToken(token: String, errorCallback: Action.Callback<Unit>) {
            try {
                MessagingPush.instance().registerDeviceToken(deviceToken = token).enqueue(errorCallback)
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Error while handling token: ${exception.message}")
                errorCallback.onResult(exception.getErrorResult())
            }
        }

        private fun handleMessageReceived(
            context: Context,
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            val handler = CustomerIOPushNotificationHandler(remoteMessage = remoteMessage)
            return handler.handleMessage(context, handleNotificationTrigger)
        }
    }

    override fun onNewToken(token: String) {
        handleNewToken(token) { }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        handleMessageReceived(this, remoteMessage)
    }
}
