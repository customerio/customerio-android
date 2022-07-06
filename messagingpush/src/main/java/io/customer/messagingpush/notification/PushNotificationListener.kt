package io.customer.messagingpush.notification

interface PushNotificationListener {
    fun onNotificationPreCompose(payload: PushNotificationPayload): PushNotificationAttributes?
}
