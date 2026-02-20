package io.customer.messaginginapp.inbox

import io.customer.messaginginapp.gist.data.model.InboxMessage

/**
 * Listener for notification inbox message changes.
 *
 * Receives real time notifications when inbox messages are added, updated, or removed.
 * Callbacks are invoked on the main thread for safe UI updates.
 *
 * **Important:** Call [NotificationInbox.removeChangeListener] when done (e.g., in `onDestroy()`)
 * to prevent memory leaks.
 */
interface NotificationInboxChangeListener {
    /**
     * Called when messages change.
     *
     * Invoked immediately with current messages when registered, then again whenever
     * messages are added, updated, or removed.
     *
     * @param messages Current inbox messages. Filtered by topic if specified during registration.
     */
    fun onMessagesChanged(messages: List<InboxMessage>)
}
