package io.customer.messagingpush

import io.customer.messagingpush.notification.PushNotificationListener

/**
 * Helper class to hold client listeners for push notifications
 */
object CustomerIOPushNotificationService {
    internal var pushNotificationListener: PushNotificationListener? = null

    /**
     * Sets the [PushNotificationListener] instance for SDK so that client app
     * can be notified for desired events
     *
     * @param listener callback instance to notify client app
     */
    fun setPushNotificationListener(listener: PushNotificationListener) {
        pushNotificationListener = listener
    }
}
