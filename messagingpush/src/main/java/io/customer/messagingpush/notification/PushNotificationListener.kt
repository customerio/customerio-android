package io.customer.messagingpush.notification

import androidx.core.app.NotificationCompat

/**
 * Interface definition for a callback that allows client app to listen on
 * events for push notifications
 */
interface PushNotificationListener {
    /**
     * Called when all attributes for the notification has been set by the SDK
     * and the notification is about to be pushed to tray. However, the pending
     * intent for notification is set once the callback is completed as it can
     * affect tracking and other metrics, thus, overriding pending intent is
     * not allowed by the SDK.
     *
     * @param payload data received to trigger the notification
     * @param builder notification builder that can be used to modify
     * notification attributes
     */
    fun onNotificationComposed(
        payload: PushNotificationPayload,
        builder: NotificationCompat.Builder
    )
}
