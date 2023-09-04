package io.customer.messagingpush.config

/**
 * Defines the behaviors for what happens when a notification is clicked.
 */
enum class NotificationClickBehavior {

    /**
     * Always restarts the activity and creates a new task stack upon clicking the notification.
     * Corresponds to Android's "Set up a regular activity PendingIntent".
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#DirectEntry).
     */
    ALWAYS_RESTART_ACTIVITY,

    /**
     * Restarts the activity only if it's necessary when the notification is clicked.
     * Corresponds to Android's "Set up a special activity PendingIntent".
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#ExtendedNotification).
     */
    RESTART_ACTIVITY_IF_NEEDED
}
