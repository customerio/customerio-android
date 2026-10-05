package io.customer.messagingpush

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.di.pushLogger
import io.customer.messagingpush.di.pushMessageProcessor
import io.customer.messagingpush.util.NotificationChannelCreator
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent

open class CustomerIOFirebaseMessagingService : FirebaseMessagingService() {

    companion object {

        private val eventBus: EventBus
            get() = SDKComponent.eventBus

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

        /**
         * Handles the Firebase Installation ID (FID) delivered once this app instance registers
         * with FCM. Call this from `FirebaseMessagingService.onRegistered` in your own service when
         * the app opts in to FID registration (firebase-messaging 25.1.0+).
         *
         * @param context reference to application context
         * @param installationId Firebase Installation ID of this app instance
         */
        @JvmStatic
        fun onRegistered(context: Context, installationId: String) {
            handleRegistered(context = context, installationId = installationId)
        }

        private fun handleRegistered(context: Context, installationId: String) {
            SDKComponent.setupAndroidComponent(context = context)
            // Firebase fires onRegistered on every register() call, including the one the SDK makes
            // at startup, which already reports the FID. Publish only when it changed.
            if (installationId == SDKComponent.android().globalPreferenceStore.getDeviceToken()) {
                SDKComponent.pushLogger.logInstallationIdUnchanged(installationId)
                return
            }
            publishDeviceToken(installationId)
        }

        private fun handleNewToken(context: Context, token: String) {
            SDKComponent.setupAndroidComponent(context = context)
            publishDeviceToken(token)
        }

        private fun publishDeviceToken(token: String) {
            eventBus.publish(Event.RegisterDeviceTokenEvent(token))
        }

        private fun handleMessageReceived(
            context: Context,
            remoteMessage: RemoteMessage,
            handleNotificationTrigger: Boolean = true
        ): Boolean {
            SDKComponent.setupAndroidComponent(context = context)
            val handler = CustomerIOPushNotificationHandler(
                pushMessageProcessor = SDKComponent.pushMessageProcessor,
                remoteMessage = remoteMessage,
                notificationChannelCreator = NotificationChannelCreator()
            )
            return handler.handleMessage(context, handleNotificationTrigger)
        }
    }

    override fun onNewToken(token: String) {
        handleNewToken(context = this, token = token)
    }

    // Not marked override: the SDK compiles against Firebase older than 25.1.0, which lacks these.
    // On 25.1.0+ they override FirebaseMessagingService's callbacks at runtime.
    open fun onRegistered(installationId: String) {
        handleRegistered(context = this, installationId = installationId)
    }

    open fun onUnregistered(installationId: String) {
        SDKComponent.pushLogger.logInstallationIdUnregistered(installationId)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        handleMessageReceived(this, remoteMessage)
    }
}
