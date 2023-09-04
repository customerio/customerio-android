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
    RESET_TASK_STACK,

    /**
     * Restarts the activity only if it's necessary when the notification is clicked.
     * Similar to Android's "Set up a special activity PendingIntent" but without forced restart.
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#ExtendedNotification).
     */
    RESTART_ACTIVITY_IF_NEEDED,

    /**
     * Always restarts the activity upon clicking the notification.
     * Will force restart the activity if it already exists.
     * Similar to RESTART_ACTIVITY_IF_NEEDED, but with forced restart.
     */
    ALWAYS_RESTART_ACTIVITY
}
