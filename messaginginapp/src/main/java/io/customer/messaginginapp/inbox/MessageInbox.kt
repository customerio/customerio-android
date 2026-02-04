package io.customer.messaginginapp.inbox

import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.state.InAppMessagingAction
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
}
