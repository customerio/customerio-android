package io.customer.messaginginapp.inbox

import io.customer.messaginginapp.gist.data.model.InboxMessage

/**
 * Listener for inbox message changes.
 *
 * Receives real time notifications when inbox messages are added, updated, or removed.
 * Callbacks are invoked on the main thread for safe UI updates.
 *
 * **Important:** Call [MessageInbox.removeChangeListener] when done (e.g., in `onDestroy()`)
 * to prevent memory leaks.
 */
interface InboxChangeListener {
    /**
     * Called when inbox messages change.
     *
     * Invoked immediately with current messages when registered, then again whenever
     * messages are added, updated, or removed.
     *
     * @param messages Current inbox messages. Filtered by topic if specified during registration.
     */
    fun onInboxChanged(messages: List<InboxMessage>)
}
