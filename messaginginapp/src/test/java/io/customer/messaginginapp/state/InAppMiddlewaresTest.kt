package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.testutils.extension.testMatchesRoute
import io.customer.sdk.core.di.SDKComponent
import org.reduxkotlin.middleware

/**
 * Test-specific middleware for processing messages in the queue.
 * This version uses our test-specific matching logic.
 */
internal fun testProcessMessages() = middleware<InAppMessagingState> { store, next, action ->
    if (action is InAppMessagingAction.ProcessMessageQueue && action.messages.isNotEmpty()) {
        val notShownMessages = action.messages
            .filter { message ->
                message.queueId != null && !store.state.shownMessageQueueIds.contains(message.queueId)
            }
            .distinctBy(Message::queueId)
            .sortedWith(compareBy(nullsLast()) { it.priority })

        val modalMessages = notShownMessages.filter { it.gistProperties.elementId == null }
        val inlineMessages = notShownMessages.filter { it.gistProperties.elementId != null }

        val isCurrentMessageDisplaying = store.state.modalMessageState is ModalMessageState.Displayed
        val isCurrentMessageBeingProcessed = store.state.modalMessageState is ModalMessageState.Loading

        next(InAppMessagingAction.ProcessMessageQueue(notShownMessages))

        val inLineMessagesToBeShown = inlineMessages
            .filter { it.testMatchesRoute(store.state.currentRoute) }
            .filter { message ->
                val elementId = message.gistProperties.elementId ?: return@filter true
                val existingState = store.state.queuedInlineMessagesState.getMessage(elementId)
                existingState !is InlineMessageState.Embedded
            }

        if (inLineMessagesToBeShown.isNotEmpty()) {
            store.dispatch(InAppMessagingAction.EmbedMessages(inLineMessagesToBeShown))
        }

        val modalMessageToBeShown = modalMessages.firstOrNull { it.testMatchesRoute(store.state.currentRoute) }

        if (modalMessageToBeShown != null && !isCurrentMessageDisplaying && !isCurrentMessageBeingProcessed) {
            store.dispatch(InAppMessagingAction.LoadMessage(modalMessageToBeShown))
        } else {
            SDKComponent.logger.debug("No message matched the criteria.")
        }
    } else {
        next(action)
    }
}

/**
 * Test-specific middleware to handle route change actions.
 */
internal fun testRouteChangeMiddleware() = middleware<InAppMessagingState> { store, next, action ->

    if (action is InAppMessagingAction.SetPageRoute && store.state.userId != null) {
        next(action)

        val currentMessage = when (val currentMessageState = store.state.modalMessageState) {
            is ModalMessageState.Displayed -> currentMessageState.message
            is ModalMessageState.Loading -> currentMessageState.message
            else -> null
        }

        if (currentMessage != null) {
            val isCurrentMessageRouteAllowedOnNewRoute = currentMessage.testMatchesRoute(action.route)
            if (!isCurrentMessageRouteAllowedOnNewRoute) {
                SDKComponent.logger.debug("Dismissing message: ${currentMessage.queueId} because route does not match current route: ${action.route}")
                store.dispatch(InAppMessagingAction.DismissMessage(message = currentMessage, shouldLog = false))
            }
        }

        store.dispatch(InAppMessagingAction.ProcessMessageQueue(store.state.messagesInQueue.toList()))
    } else {
        next(action)
    }
}

/**
 * Test-specific middleware for modal message display that doesn't rely on Android components.
 */
internal fun testDisplayModalMessageMiddleware() = middleware<InAppMessagingState> { store, next, action ->
    if (action is InAppMessagingAction.LoadMessage) {
        if (action.message.gistProperties.elementId == null) {
            if (store.state.modalMessageState !is ModalMessageState.Displayed) {
                next(action)
            } else {
                next(InAppMessagingAction.ReportError("A message is already being shown or cancelled"))
            }
        } else {
            next(action)
        }
    } else {
        next(action)
    }
}
