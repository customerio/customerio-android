package io.customer.messagingpush

import io.customer.messagingpush.notification.PushNotificationListener

object CustomerIOPushNotificationService {
    internal var pushNotificationListener: PushNotificationListener? = null

    fun setPushNotificationListener(listener: PushNotificationListener) {
        pushNotificationListener = listener
    }
}
