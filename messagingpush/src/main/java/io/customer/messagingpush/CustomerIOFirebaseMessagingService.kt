package io.customer.messagingpush

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.sdk.CustomerIO

open class CustomerIOFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        /**
         * Handles receiving an incoming push notification.
         *
         * Call this from a custom [FirebaseMessagingService] to pass push messages to
         * CustomerIO SDK for tracking and rendering
         * @param context reference to application context
         * @param remoteMessage Remote message received from Firebase in
         * [FirebaseMessagingService.onMessageReceived]
         * @param handleNotificationTrigger indicating if the local notification should be triggered
         * @return Boolean indicating whether this will be handled by CustomerIO
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
         * Handles receiving an incoming push notification.
         *
         * Call this from a custom [FirebaseMessagingService] to pass push messages to
         * CustomerIO SDK for tracking and rendering
         * @param remoteMessage Remote message received from Firebase in
         * [FirebaseMessagingService.onMessageReceived]
         * @param handleNotificationTrigger indicating if the local notification should be triggered
         * @return Boolean indicating whether this will be handled by CustomerIO
         */
        @Deprecated(
            "This method is deprecated and will be removed in future releases. Use the one with context",
            ReplaceWith("onMessageReceived(context = applicationContext, remoteMessage = remoteMessage, handleNotificationTrigger = handleNotificationTrigger)")
        )
        @JvmOverloads
        fun onMessageReceived(
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            val diGraph = getInstanceOrNull()?.diGraph ?: return false
            return handleMessageReceived(diGraph.context, remoteMessage, handleNotificationTrigger)
        }

        // Only to be used by deprecated methods relying on DI graphs from CustomerIO instance, should be removed with deprecated methods
        private fun getInstanceOrNull(): CustomerIO? {
            return try {
                CustomerIO.instance()
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Handles new or refreshed token
         * Call this from [FirebaseMessagingService] to register the new device token
         *
         * @param token new or refreshed token
         */
        @Deprecated(
            "This method is deprecated and will be removed in future releases. Use the one with context",
            ReplaceWith("onNewToken(context = applicationContext, token = token)")
        )
        fun onNewToken(token: String) {
            val diGraph = getInstanceOrNull()?.diGraph ?: return
            handleNewToken(diGraph.context, token)
        }

        /**
         * Handles new or refreshed token
         * Call this from [FirebaseMessagingService] to register the new device token
         *
         * @param context reference to application context
         * @param token new or refreshed token
         */
        fun onNewToken(context: Context, token: String) {
            handleNewToken(context = context, token = token)
        }

        private fun handleNewToken(context: Context, token: String) {
            CustomerIO.instanceOrNull(context)?.registerDeviceToken(deviceToken = token)
        }

        private fun handleMessageReceived(
            context: Context,
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            // if CustomerIO instance isn't initialized, we can't handle the notification
            if (CustomerIO.instanceOrNull(context, listOf(ModuleMessagingPushFCM())) == null) {
                return false
            }
            val handler = CustomerIOPushNotificationHandler(remoteMessage = remoteMessage)
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
