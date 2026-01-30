package io.customer.messaginginapp.inbox

import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.di.SDKComponent
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
class MessageInbox(private val coroutineScope: CoroutineScope) {
    private val inAppMessagingManager: InAppMessagingManager
        get() = SDKComponent.inAppMessagingManager
    private val currentState: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()

    /**
     * Retrieves the current list of inbox messages synchronously.
     *
     * @return List of inbox messages for the current user
     */
    @Suppress("RedundantSuspendModifier")
    suspend fun getMessages(): List<InboxMessage> {
        // Intentionally suspend for API stability
        return currentState.inboxMessages.toList()
    }

    /**
     * Retrieves inbox messages asynchronously via callback.
     *
     * @param callback Called with [Result] containing the list of messages or an error
     * if failed to retrieve
     */
    fun getMessages(callback: (Result<List<InboxMessage>>) -> Unit) {
        coroutineScope.launch {
            try {
                val messages = getMessages()
                callback(Result.success(messages))
            } catch (ex: Exception) {
                callback(Result.failure(ex))
            }
        }
    }
}
