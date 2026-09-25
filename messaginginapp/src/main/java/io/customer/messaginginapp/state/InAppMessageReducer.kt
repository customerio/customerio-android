package io.customer.messaginginapp.state

import io.customer.sdk.core.di.SDKComponent
import java.util.UUID
import org.reduxkotlin.Reducer

internal val inAppMessagingReducer: Reducer<InAppMessagingState> = { state, action ->
    val newState = when (action) {
        is InAppMessagingAction.Initialize ->
            state.copy(
                siteId = action.siteId,
                dataCenter = action.dataCenter,
                environment = action.environment,
                colorScheme = action.colorScheme,
                sessionId = UUID.randomUUID().toString()
            )

        is InAppMessagingAction.SetColorScheme ->
            state.copy(colorScheme = action.colorScheme)

        is InAppMessagingAction.SetPageRoute ->
            state.copy(currentRoute = action.route)

        is InAppMessagingAction.SetUserIdentifier ->
            state.copy(userId = action.user)

        is InAppMessagingAction.SetAnonymousIdentifier ->
            state.copy(anonymousId = action.anonymousId)

        is InAppMessagingAction.ClearMessageQueue ->
            if (action.isContentEmpty) {
                // A no-content (HTTP 204) response is authoritative for messages that have not
                // been displayed. Keep an actively embedded message until dismissal or view
                // release, shown queue IDs so stale data cannot redisplay it, and inbox deletion
                // tombstones until Reset so a stale poll cannot resurrect deleted inbox content.
                state.copy(
                    queuedInlineMessagesState = state.queuedInlineMessagesState.reconcileMessages(
                        messages = emptyList(),
                        authoritativeMessages = emptyList(),
                        shownMessageQueueIds = state.shownMessageQueueIds,
                        currentRoute = state.currentRoute
                    ),
                    messagesInQueue = emptySet(),
                    inboxMessages = emptySet()
                )
            } else {
                // A failed poll or an uncached 304 is not authoritative. Retain inline messages so
                // a message that was not eligible for the previous route can still become
                // available after a route change. Modal messages keep their existing clear-on-
                // failure behavior, and inbox messages remain available as stale content.
                state.copy(messagesInQueue = state.messagesInQueue.filter { it.isEmbedded }.toSet())
            }

        is InAppMessagingAction.ProcessMessageQueue ->
            state.copy(messagesInQueue = action.messages.toSet())

        is InAppMessagingAction.ProcessInboxMessages -> {
            // Drop any server-echoed message the user already dismissed locally (eventual
            // consistency / cached /queue can resurrect a just-deleted message). At the same time,
            // prune tombstones the server has caught up on: a tombstoned id that is NOT in the
            // incoming list is safe to forget, so a future legitimate re-delivery is not suppressed.
            val incomingIds = action.messages.mapTo(HashSet()) { it.queueId }
            // Only a NON-EMPTY poll is authoritative enough to prune tombstones. An empty/transient
            // response (momentary empty, cache miss) carries no "server forgot this id" signal, so
            // keep all tombstones then — otherwise a later poll re-echoing a dismissed id would
            // resurrect it (the very bug the tombstone prevents).
            val retainedTombstones = if (incomingIds.isEmpty()) {
                state.deletedInboxMessageIds
            } else {
                state.deletedInboxMessageIds.intersect(incomingIds)
            }
            val filteredMessages = action.messages
                .filterNot { it.queueId in retainedTombstones }
                .toSet()
            state.copy(
                inboxMessages = filteredMessages,
                deletedInboxMessageIds = retainedTombstones
            )
        }

        is InAppMessagingAction.InboxAction.UpdateOpened -> {
            // Update opened status for given message
            val queueId = action.message.queueId
            val updatedMessages = state.inboxMessages.map { message ->
                if (message.queueId == queueId) {
                    message.copy(opened = action.opened)
                } else {
                    message
                }
            }.toSet()
            state.copy(inboxMessages = updatedMessages)
        }

        is InAppMessagingAction.InboxAction.DeleteMessage -> {
            // Remove deleted message from state and record a tombstone so a subsequent poll whose
            // /queue response still echoes this (already deleted) message cannot resurrect it.
            val queueId = action.message.queueId
            val updatedMessages = state.inboxMessages.filterNot {
                it.queueId == queueId
            }.toSet()
            state.copy(
                inboxMessages = updatedMessages,
                deletedInboxMessageIds = state.deletedInboxMessageIds + queueId
            )
        }

        is InAppMessagingAction.InboxAction.TrackClicked ->
            state // No state changes for tap action

        is InAppMessagingAction.SetPollingInterval ->
            state.copy(pollInterval = action.interval)

        is InAppMessagingAction.SetSseEnabled ->
            state.copy(sseEnabled = action.enabled)

        is InAppMessagingAction.SetInboxEnabled ->
            state.copy(isInboxEnabled = action.enabled)

        is InAppMessagingAction.EngineAction.MessageLoadingFailed -> state.withMessageDismissed(
            message = action.message,
            shouldMarkAsShown = false
        )

        is InAppMessagingAction.LoadMessage ->
            state.copy(modalMessageState = ModalMessageState.Loading(action.message))

        is InAppMessagingAction.Reset -> state.copy(
            userId = null,
            anonymousId = null,
            currentRoute = null,
            sessionId = UUID.randomUUID().toString(),
            modalMessageState = ModalMessageState.Initial,
            queuedInlineMessagesState = QueuedInlineMessagesState(),
            messagesInQueue = emptySet(),
            inboxMessages = emptySet(),
            deletedInboxMessageIds = emptySet(),
            shownMessageQueueIds = emptySet(),
            sseEnabled = false,
            isInboxEnabled = false
        )

        is InAppMessagingAction.EmbedMessages -> {
            // Handling embedding messages in a single loop for better performance
            val newEmbeddedMessagesState = action.messages.fold(state.queuedInlineMessagesState) { accState, message ->
                message.embeddedElementId?.let { elementId ->
                    accState.addMessage(message, elementId)
                } ?: accState
            }
            state.copy(queuedInlineMessagesState = newEmbeddedMessagesState)
        }

        is InAppMessagingAction.ReconcileInlineMessages -> state.copy(
            queuedInlineMessagesState = state.queuedInlineMessagesState.reconcileMessages(
                messages = action.messages,
                authoritativeMessages = action.authoritativeMessages,
                shownMessageQueueIds = state.shownMessageQueueIds,
                currentRoute = state.currentRoute
            )
        )

        is InAppMessagingAction.SetInlineMessageViewAttached -> {
            action.message.queueId?.let { queueId ->
                state.copy(
                    queuedInlineMessagesState = state.queuedInlineMessagesState.setMessageViewAttached(
                        queueId = queueId,
                        isAttached = action.isAttached
                    )
                )
            } ?: state
        }

        is InAppMessagingAction.DisplayMessage -> {
            action.message.queueId?.let { queueId ->
                // If the message should be tracked shown when it is displayed, add the queueId to shownMessageQueueIds.
                val shownMessageQueueIds = if (action.shouldMarkMessageAsShown()) {
                    state.shownMessageQueueIds + queueId
                } else {
                    state.shownMessageQueueIds
                }

                // Remove the message from the queue
                val filteredQueue = state.messagesInQueue.filterNot { it.queueId == queueId }.toSet()

                if (action.message.isEmbedded) {
                    // Update embedded message state
                    val elementId = action.message.embeddedElementId ?: ""
                    state.withUpdatedEmbeddedMessage(
                        queueId = queueId,
                        newState = InlineMessageState.Embedded(action.message, elementId),
                        shownMessageQueueIds = shownMessageQueueIds,
                        messagesInQueue = filteredQueue
                    )
                } else {
                    // Update modal message state
                    state.copy(
                        modalMessageState = ModalMessageState.Displayed(action.message),
                        shownMessageQueueIds = shownMessageQueueIds,
                        messagesInQueue = filteredQueue
                    )
                }
            } ?: state
        }

        is InAppMessagingAction.DismissMessage -> state.withMessageDismissed(
            message = action.message,
            shouldMarkAsShown = action.shouldMarkMessageAsShown()
        )

        is InAppMessagingAction.EngineAction.Tap ->
            state // No state changes for tap action

        is InAppMessagingAction.ReportError ->
            state // No state changes for error reporting

        else -> {
            // Log unexpected action for debugging
            SDKComponent.logger.debug("Unhandled action received: $action")
            state
        }
    }

    // Log state changes
    val changes = state.diff(newState)
    if (changes.isNotEmpty()) {
        SDKComponent.logger.debug("Store: state changes after action:")
        for ((property, values) in changes) {
            SDKComponent.logger.debug("  $property: ${values.first} -> ${values.second}")
        }
    } else {
        SDKComponent.logger.debug("Store: no state changes after action")
    }

    newState
}
