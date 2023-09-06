package io.customer.messagingpush.config

/**
 * Defines the behaviors for what happens when a notification is clicked.
 */
enum class NotificationClickBehavior {

    /**
     * Resets the task stack to include the deep-linked activity 'D'.
     * - Example 1: Stack (A -> B -> C) becomes (D) if D is the deep-linked activity.
     * - Example 2: Stack (A -> B -> C) changes to (A -> D) if D is the deep-linked activity and A is the root of the task stack provided by callback.
     *
     * This is similar to Android's "Set up a regular activity PendingIntent."
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#DirectEntry).
     */
    RESET_TASK_STACK,

    /**
     * Adds the deep-linked activity 'D' to the existing stack only if it's not already there.
     * - Example: Stack (A -> B) becomes (A -> B -> D) if D is the deep-linked activity and not already in the stack.
     * - Example: Stack (A -> B -> D) stays as (A -> B -> D) if D is the deep-linked activity and is already in the stack.
     *
     * Works well for activities with launch modes other than `standard`. The same activity instance will be reused and receive the data in `onNewIntent`.
     *
     * This is similar to Android's "Set up a special activity PendingIntent."
     * For more info, see [Android Documentation](https://developer.android.com/develop/ui/views/notifications/navigation#ExtendedNotification).
     */
    ACTIVITY_PREVENT_RESTART,

    /**
     * Starts the deep-linked activity without adding any intent flags.
     * - Example: Stack (A -> B) becomes (A -> B -> D) if D is the deep-linked target activity.
     *
     * This behavior relies on the launch mode or flags specified for the activity in the Android manifest.
     */
    ACTIVITY_NO_FLAGS
}
