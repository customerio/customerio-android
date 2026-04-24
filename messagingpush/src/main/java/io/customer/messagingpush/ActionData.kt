package io.customer.messagingpush

import android.app.PendingIntent

/**
 * Represents a single action button on a live notification.
 *
 * @property label The user-visible button text (e.g. "View Order")
 * @property link Deep link URI fired when the action is tapped
 * @property pendingIntent The PendingIntent that routes through
 *           [NotificationClickReceiverActivity] for tracking and deep link handling
 */
internal data class ActionData(
    val label: String,
    val link: String,
    val pendingIntent: PendingIntent
)
