package io.customer.messaginginapp.inbox

import androidx.annotation.MainThread
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages inbox messages for the current user.
 *
 * Inbox messages are persistent messages that users can view, mark as read/unread, and delete.
 * Messages are automatically fetched and kept in sync for identified users.
 *
 * Example usage:
 * ```
 * val inbox = CustomerIO.instance().inAppMessaging().inbox()
 *
 * // Get messages synchronously
 * inbox.getMessages()
 * ```
 */
class NotificationInbox internal constructor(
    private val logger: Logger,
    private val coroutineScope: CoroutineScope,
    private val dispatchersProvider: DispatchersProvider,
    private val inAppMessagingManager: InAppMessagingManager
) {
    private val currentState: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()

    // CopyOnWriteArraySet provides thread safe iteration without blocking
    // Ideal for this use case where iteration (on state changes) is more frequent than add/remove
    private val listeners = CopyOnWriteArraySet<ListenerRegistration>()

    init {
        // Subscribe to inbox messages on initialization to simplify listener management
        // and eliminate race conditions from conditional subscription
        inAppMessagingManager.subscribeToAttribute(
            selector = { state -> state.inboxMessages },
            areEquivalent = { old, new -> old == new }
        ) { inboxMessages ->
            notifyAllListeners(messages = inboxMessages.toList())
        }
    }

    /**
     * Retrieves the current list of inbox messages synchronously.
     *
     * @param topic Optional topic filter. If provided, listener only receives messages
     *              that have this topic in their topics list. If null, all messages are delivered.
     * @return List of inbox messages for the current user
     */
    @JvmOverloads
    @Suppress("RedundantSuspendModifier")
    suspend fun getMessages(topic: String? = null): List<InboxMessage> {
        // Intentionally suspend for API stability
        val messages = currentState.inboxMessages.toList()
        return filterMessagesByTopic(messages, topic)
    }

    /**
     * Fetches all inbox messages asynchronously via callback.
     *
     * @param callback Called with [Result] containing the list of messages or an error
     * if failed to retrieve
     */
    fun fetchMessages(callback: (Result<List<InboxMessage>>) -> Unit) {
        fetchMessagesWithCallback(null, callback)
    }

    /**
     * Fetches inbox messages for a specific topic asynchronously via callback.
     *
     * @param topic Optional topic filter. If provided, listener only receives messages
     *              that have this topic in their topics list. If null, all messages are delivered.
     * @param callback Called with [Result] containing the list of messages or an error
     * if failed to retrieve
     */
    fun fetchMessages(topic: String?, callback: (Result<List<InboxMessage>>) -> Unit) {
        fetchMessagesWithCallback(topic, callback)
    }

    // Internal helper to avoid code duplication between callback-based overloads
    private fun fetchMessagesWithCallback(topic: String?, callback: (Result<List<InboxMessage>>) -> Unit) {
        coroutineScope.launch {
            try {
                val messages = getMessages(topic)
                callback(Result.success(messages))
            } catch (ex: Exception) {
                callback(Result.failure(ex))
            }
        }
    }

    /**
     * Registers a listener for inbox changes.
     *
     * Must be called from main thread. The listener is immediately notified with current state,
     * then receives all future updates.
     *
     * IMPORTANT: Call [removeChangeListener] when done (e.g., in Activity.onDestroy or Fragment.onDestroyView)
     * to prevent memory leaks.
     *
     * @param listener The listener to receive inbox updates
     * @param topic Optional topic filter. If provided, listener only receives messages
     *              that have this topic in their topics list. If null, all messages are delivered.
     */
    @JvmOverloads
    @MainThread
    fun addChangeListener(listener: NotificationInboxChangeListener, topic: String? = null) {
        val registration = ListenerRegistration(listener, topic)
        listeners.add(registration)

        // Notify immediately with current state
        // Since we're on main thread and subscription coroutines are queued on main dispatcher,
        // this should be completed atomically before any queued subscription notifications can execute
        val messages = currentState.inboxMessages.toList()
        val filteredMessages = filterMessagesByTopic(messages, topic)
        notifyListener(listener, filteredMessages)
    }

    /**
     * Unregisters a listener for inbox changes.
     * Removes all registrations of this listener, regardless of topic filters.
     */
    fun removeChangeListener(listener: NotificationInboxChangeListener) {
        listeners.forEach { registration ->
            if (registration.listener == listener) {
                listeners.remove(registration)
            }
        }
    }

    /**
     * Notifies all registered listeners with filtered messages.
     * Prepares notifications on background thread, then switches to main thread for callbacks.
     */
    private fun notifyAllListeners(messages: List<InboxMessage>) {
        // Prepare all data on background thread to avoid blocking main thread
        val notificationsToSend = listeners.map { (listener, topic) ->
            listener to filterMessagesByTopic(messages, topic)
        }

        // Switch to main thread for notifications
        coroutineScope.launch(dispatchersProvider.main) {
            notificationsToSend.forEach { (listener, filteredMessages) ->
                notifyListener(listener, filteredMessages)
            }
        }
    }

    /**
     * Filters messages by topic if specified and sorts by sentAt (newest first).
     * Topic matching is case-insensitive.
     *
     * @param messages The messages to filter
     * @param topic The topic filter, or null to return all messages
     * @return Filtered and sorted list of messages
     */
    private fun filterMessagesByTopic(messages: List<InboxMessage>, topic: String?): List<InboxMessage> {
        val filteredMessages = if (topic == null) {
            messages
        } else {
            messages.filter { message ->
                message.topics.any { it.equals(topic, ignoreCase = true) }
            }
        }
        return filteredMessages.sortedByDescending { it.sentAt }
    }

    /**
     * Notifies a single listener with messages, handling errors gracefully.
     * Must be called on main thread (callers are responsible for dispatching to main).
     *
     * @param listener The listener to notify
     * @param messages The messages to send to the listener
     */
    @MainThread
    private fun notifyListener(listener: NotificationInboxChangeListener, messages: List<InboxMessage>) {
        try {
            listener.onMessagesChanged(messages)
        } catch (ex: Exception) {
            // Log and continue to prevent one bad listener from breaking others
            logger.error("Error notifying inbox listener: ${ex.message}")
        }
    }

    /**
     * Marks an inbox message as opened/read.
     * Updates local state immediately and syncs with the server.
     *
     * @param message The inbox message to mark as opened
     */
    fun markMessageOpened(message: InboxMessage) {
        inAppMessagingManager.dispatch(
            InAppMessagingAction.InboxAction.UpdateOpened(
                message = message,
                opened = true
            )
        )
    }

    /**
     * Marks an inbox message as unopened/unread.
     * Updates local state immediately and syncs with the server.
     *
     * @param message The inbox message to mark as unopened
     */
    fun markMessageUnopened(message: InboxMessage) {
        inAppMessagingManager.dispatch(
            InAppMessagingAction.InboxAction.UpdateOpened(
                message = message,
                opened = false
            )
        )
    }

    /**
     * Marks an inbox message as deleted.
     * Removes the message from local state and syncs with the server.
     *
     * @param message The inbox message to mark as deleted
     */
    fun markMessageDeleted(message: InboxMessage) {
        inAppMessagingManager.dispatch(
            InAppMessagingAction.InboxAction.DeleteMessage(message)
        )
    }

    /**
     * Tracks a click event for an inbox message.
     * Sends metric event to data pipelines to track message interaction.
     *
     * @param message The inbox message that was clicked
     * @param actionName Optional name of the action clicked (e.g., "view_details", "dismiss")
     */
    @JvmOverloads
    fun trackMessageClicked(message: InboxMessage, actionName: String? = null) {
        inAppMessagingManager.dispatch(
            InAppMessagingAction.InboxAction.TrackClicked(
                message = message,
                actionName = actionName
            )
        )
    }

    /**
     * Wrapper class to store listener with optional topic filter.
     */
    private data class ListenerRegistration(
        val listener: NotificationInboxChangeListener,
        val topic: String? = null
    )
}
