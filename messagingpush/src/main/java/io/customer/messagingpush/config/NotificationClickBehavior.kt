package io.customer.messagingpush.config

/**
 * Defines the behaviors for what happens when a notification is clicked.
 */
enum class NotificationClickBehavior {

    /**
     * Creates a new task stack, clearing any existing one upon clicking the notification.
     * Similar to Android's "Set up a regular activity PendingIntent".
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#DirectEntry).
     */
    TASK_RESET_ALWAYS,

    /**
     * Restarts the activity only if it's necessary when the notification is clicked.
     * Similar to Android's "Set up a special activity PendingIntent".
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#ExtendedNotification).
     */
    ACTIVITY_RESTART_IF_NEEDED,

    /**
     * Always restarts the activity upon clicking the notification.
     * Similar to ACTIVITY_RESTART_IF_NEEDED, but with forced restart (if it is already on top).
     */
    ACTIVITY_RESTART_ALWAYS
}
