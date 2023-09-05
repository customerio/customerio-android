package io.customer.messagingpush.config

/**
 * Defines the behaviors for what happens when a notification is clicked.
 */
enum class NotificationClickBehavior {

    /**
     * Always creates a new task stack and clears any existing one upon clicking the notification.
     * - Example 1: Stack (A -> B -> C) becomes (D) if D is the target activity.
     * - Example 2: Stack (A -> B -> C) changes to (A -> D) if D is the target activity and A is the root of the task stack provided by callback.
     *
     * Similar to Android's "Set up a regular activity PendingIntent."
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#DirectEntry).
     */
    TASK_RESET_ALWAYS,

    /**
     * Restarts the target activity only if necessary when the notification is clicked.
     * - Example 1: Stack (A -> B) becomes (A -> B -> D) if D is the target activity and not in the stack.
     * - Example 2: Stack (A -> B -> D) remains (A -> B -> D) if D is the target activity and is already in the stack. D will not be restarted.
     *
     * Similar to Android's "Set up a special activity PendingIntent."
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#ExtendedNotification).
     */
    ACTIVITY_RESTART_IF_NEEDED,

    /**
     * Always restarts the target activity upon clicking the notification.
     * - Example 1: Stack (A -> B) becomes (A -> B -> D) if D is the target activity and not in the stack.
     * - Example 2: Stack (A -> B -> D) remains (A -> B -> D) if D is the target activity and is already in the stack. D will be restarted.
     *
     * This behavior is similar to ACTIVITY_RESTART_IF_NEEDED, but it forces a restart of the activity if it is already on top of the stack.
     */
    ACTIVITY_RESTART_ALWAYS
}
