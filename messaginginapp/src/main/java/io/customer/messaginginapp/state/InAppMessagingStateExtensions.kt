package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.data.model.Message

/**
 * Updates [InAppMessagingState] to reflect the new state of an embedded messages.
 *
 * @param queueId The queueId of the embedded message to update.
 * @param newState The new state of the embedded message.
 * @param shownMessageQueueIds The set of queueIds that have been shown.
 * @param messagesInQueue The set of messages in the queue.
 * @return Updated [InAppMessagingState] with new state of the embedded message.
 */
internal fun InAppMessagingState.withUpdatedEmbeddedMessage(
    queueId: String,
    newState: InlineMessageState,
    shownMessageQueueIds: Set<String> = this.shownMessageQueueIds,
    messagesInQueue: Set<Message> = this.messagesInQueue
): InAppMessagingState {
    val updatedEmbeddedMessagesState = queuedInlineMessagesState.updateMessageState(queueId, newState)
    return copy(
        queuedInlineMessagesState = updatedEmbeddedMessagesState,
        shownMessageQueueIds = shownMessageQueueIds,
        messagesInQueue = messagesInQueue
    )
}

/**
 * Updates [InAppMessagingState] to reflect the dismissal of current in-app message.
 * If the message is embedded, it updates the state of the embedded message.
 * If the message is modal, it updates the modal message state.
 *
 * @param message The message from current action.
 * @param shouldMarkAsShown Indicates whether the message should be marked as shown when dismissed.
 * @return Updated [InAppMessagingState] with the dismissed message.
 */
internal fun InAppMessagingState.withMessageDismissed(
    message: Message,
    shouldMarkAsShown: Boolean
): InAppMessagingState {
    var shownMessageQueueIds = this.shownMessageQueueIds
    // If the message should be tracked shown when it is dismissed, add the queueId to shownMessageQueueIds.
    if (shouldMarkAsShown && message.queueId != null) {
        shownMessageQueueIds = shownMessageQueueIds + message.queueId
    }

    when {
        message.isEmbedded -> {
            // For embedded messages
            return when {
                message.queueId != null -> {
                    // Update embedded message state if it has a queueId
                    this.withUpdatedEmbeddedMessage(
                        queueId = message.queueId,
                        newState = InlineMessageState.Dismissed(message),
                        shownMessageQueueIds = shownMessageQueueIds
                    )
                }

                else -> {
                    // For embedded messages without queueId
                    // Just return the state unchanged
                    this
                }
            }
        }

        else -> {
            // Handle modal message
            return this.copy(
                modalMessageState = ModalMessageState.Dismissed(message),
                shownMessageQueueIds = shownMessageQueueIds
            )
        }
    }
}
