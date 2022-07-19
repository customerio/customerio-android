package io.customer.messagingpush.notification

import android.os.Bundle

/**
 * Data class to hold push notification information
 *
 * @property bundle copy of bundle containing the data received
 */
data class PushNotificationPayload(
    private val bundle: Bundle
)
