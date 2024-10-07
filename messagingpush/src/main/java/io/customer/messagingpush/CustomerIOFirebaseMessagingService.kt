package io.customer.messagingpush

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent

open class CustomerIOFirebaseMessagingService : FirebaseMessagingService() {

    companion object {

        private val eventBus = SDKComponent.eventBus

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
        @JvmStatic
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
         * @param context reference to application context
         * @param token new or refreshed token
         */
        @JvmStatic
        fun onNewToken(context: Context, token: String) {
            handleNewToken(context = context, token = token)
        }

        private fun handleNewToken(context: Context, token: String) {
            SDKComponent.setupAndroidComponent(context = context)
            eventBus.publish(
                Event.RegisterDeviceTokenEvent(token)
            )
        }

        private fun handleMessageReceived(
            context: Context,
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            SDKComponent.setupAndroidComponent(context = context)
            val handler = CustomerIOPushNotificationHandler(
                pushMessageProcessor = SDKComponent.pushMessageProcessor,
                remoteMessage = remoteMessage
            )
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
