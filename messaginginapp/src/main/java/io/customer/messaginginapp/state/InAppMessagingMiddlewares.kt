package io.customer.messaginginapp.state

import android.content.Intent
import com.google.gson.Gson
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.di.gistSdk
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.matchesRoute
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistModalActivity
import io.customer.messaginginapp.gist.utilities.ModalMessageParser
import io.customer.sdk.core.di.SDKComponent
import org.reduxkotlin.Store
import org.reduxkotlin.middleware

/**
 * Middleware to log actions and state changes.
 */
internal fun loggerMiddleware() = middleware<InAppMessagingState> { store, next, action ->
    SDKComponent.logger.debug("Store: action: $action")
    SDKComponent.logger.debug("Store: state before reducer: ${store.state}")

    // continue passing the original action down the middleware chain
    next(action)
}

/**
 * Middleware to log errors.
 */
internal fun errorLoggerMiddleware() = middleware<InAppMessagingState> { _, next, action ->
    if (action is InAppMessagingAction.ReportError) {
        SDKComponent.logger.error("Error: ${action.message}")
    }
    next(action)
}

/**
 * Middleware to handle gist logging for message.
 */
internal fun gistLoggingMessageMiddleware() = middleware<InAppMessagingState> { _, next, action ->
    when (action) {
        is InAppMessagingAction.DismissMessage -> handleMessageDismissal(action, next)
        is InAppMessagingAction.DisplayMessage -> handleMessageDisplay(action, next)
        else -> next(action)
    }
}

private fun handleMessageDismissal(action: InAppMessagingAction.DismissMessage, next: (Any) -> Any) {
    // Log message close only if message should be tracked as shown on dismiss action
    if (action.shouldMarkMessageAsShown()) {
        SDKComponent.logger.debug("Persistent message dismissed, logging view for message: ${action.message}, shouldLog: ${action.shouldLog}, viaCloseAction: ${action.viaCloseAction}")
        SDKComponent.gistQueue.logView(action.message)
        SDKComponent.gistSdk.fetchInAppMessages()
    } else {
        SDKComponent.logger.debug("Message dismissed, not logging view for message: ${action.message}, shouldLog: ${action.shouldLog}, viaCloseAction: ${action.viaCloseAction}")
    }
    next(action)
}

private fun handleMessageDisplay(action: InAppMessagingAction.DisplayMessage, next: (Any) -> Any) {
    // Log message view only if message should be tracked as shown on display action
    if (action.shouldMarkMessageAsShown()) {
        SDKComponent.logger.debug("Message shown, logging view for message: ${action.message}")
        SDKComponent.gistQueue.logView(action.message)
    } else {
        SDKComponent.logger.debug("Persistent message shown, not logging view for message: ${action.message}")
    }
    next(action)
}

/**
 * Middleware to handle modal message display actions.
 */
internal fun displayModalMessageMiddleware() = middleware { store, next, action ->
    if (action is InAppMessagingAction.LoadMessage) {
        handleModalMessageDisplay(store, action, next)
    } else {
        next(action)
    }
}

private fun handleModalMessageDisplay(store: Store<InAppMessagingState>, action: InAppMessagingAction.LoadMessage, next: (Any) -> Any) {
    if (store.state.modalMessageState !is ModalMessageState.Displayed) {
        val context = SDKComponent.android().applicationContext
        SDKComponent.logger.debug("Showing message: ${action.message} with position: ${action.position} and context: $context")
        val intent = GistModalActivity.newIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(ModalMessageParser.EXTRA_IN_APP_MESSAGE, Gson().toJson(action.message))
            putExtra(ModalMessageParser.EXTRA_IN_APP_MODAL_POSITION, action.position?.toString())
        }
        context.startActivity(intent)
        next(action)
    } else {
        next(InAppMessagingAction.ReportError("A message is already being shown or cancelled"))
    }
}

/**
 * Middleware to handle user change actions.
 */
internal fun userChangeMiddleware() = middleware<InAppMessagingState> { store, next, action ->
    when {
        // the state parameters that are independent of the user need to be set
        // so that when the user is set we can display the messages right away
        action is InAppMessagingAction.Initialize -> next(action)
        action is InAppMessagingAction.SetUserIdentifier -> next(action)
        action is InAppMessagingAction.SetPageRoute -> next(action)
        store.state.userId != null -> next(action)
        else -> next(InAppMessagingAction.ReportError("User is not set."))
    }
}

/**
 * Middleware to handle route change actions.
 */
internal fun routeChangeMiddleware() = middleware<InAppMessagingState> { store, next, action ->

    if (action is InAppMessagingAction.SetPageRoute && store.state.userId != null) {
        // update the current route
        next(action)

        // cancel the current message if the route rule does not match
        val currentMessage = when (val currentMessageState = store.state.modalMessageState) {
            is ModalMessageState.Displayed -> currentMessageState.message
            is ModalMessageState.Loading -> currentMessageState.message
            else -> null
        }

        // if there is no active message or the message route matches the current route, continue
        if (currentMessage != null) {
            val currentMessageRouteRule = currentMessage.gistProperties.routeRule
            val isCurrentMessageRouteAllowedOnNewRoute = currentMessageRouteRule == null || runCatching { currentMessageRouteRule.toRegex().matches(action.route) }.getOrNull() ?: true
            if (!isCurrentMessageRouteAllowedOnNewRoute) {
                SDKComponent.logger.debug("Dismissing message: ${currentMessage.queueId} because route does not match current route: ${action.route}")
                store.dispatch(InAppMessagingAction.DismissMessage(message = currentMessage, shouldLog = false))
            }
        }

        // process the messages in the queue to check if there is a message to be shown
        store.dispatch(InAppMessagingAction.ProcessMessageQueue(store.state.messagesInQueue.toList()))
    } else {
        next(action)
    }
}

/**
 * Middleware to process messages in the queue.
 */
internal fun processMessages() = middleware<InAppMessagingState> { store, next, action ->
    if (action is InAppMessagingAction.ProcessMessageQueue && action.messages.isNotEmpty()) {
        val notShownMessages = action.messages
            .filter { message ->
                // filter out the messages that are already shown
                message.queueId != null && !store.state.shownMessageQueueIds.contains(message.queueId)
            }
            .distinctBy(Message::queueId)
            .sortedWith(compareBy(nullsLast()) { it.priority })

        val modalMessages = notShownMessages.filter { it.gistProperties.elementId == null }
        val inlineMessages = notShownMessages.filter { it.gistProperties.elementId != null }

        val isCurrentMessageDisplaying = store.state.modalMessageState is ModalMessageState.Displayed
        val isCurrentMessageBeingProcessed = store.state.modalMessageState is ModalMessageState.Loading

        // update the state with the messages in the queue that are not shown
        // because in the next steps we will check if there is a message to be shown and display them
        next(InAppMessagingAction.ProcessMessageQueue(notShownMessages))

        // Handle embedded messages
        val inLineMessagesToBeShown = inlineMessages
            .filter { it.matchesRoute(store.state.currentRoute) }
            .filter { message ->
                // Ensure no duplicate embedded messages for the same elementId in the active state
                val elementId = message.gistProperties.elementId ?: return@filter true
                val existingState = store.state.queuedInlineMessagesState.getMessage(elementId)
                existingState !is InlineMessageState.Embedded
            }

        if (inLineMessagesToBeShown.isNotEmpty()) {
            store.dispatch(InAppMessagingAction.EmbedMessages(inLineMessagesToBeShown))
        }

        // Handle modal messages
        val modalMessageToBeShown = modalMessages.firstOrNull { it.matchesRoute(store.state.currentRoute) }

        if (modalMessageToBeShown != null && !isCurrentMessageDisplaying && !isCurrentMessageBeingProcessed) {
            // Load the message to be shown
            store.dispatch(InAppMessagingAction.LoadMessage(modalMessageToBeShown))
        } else {
            // Handle the case where no message matches the criteria.
            // This might involve logging, dispatching another action, or simply doing nothing.
            SDKComponent.logger.debug("No message matched the criteria.")
        }
    } else {
        // Continue passing the original action down the middleware chain
        next(action)
    }
}

/**
 * Middleware to handle Gist listener actions.
 *
 * @param gistListener The Gist listener.
 */
internal fun gistListenerMiddleware(gistListener: GistListener?) = middleware<InAppMessagingState> { _, next, action ->
    when (action) {
        is InAppMessagingAction.EmbedMessages -> {
            action.messages.forEach {
                it.embeddedElementId?.let { elementId ->
                    gistListener?.embedMessage(it, elementId)
                }
            }
        }

        is InAppMessagingAction.DisplayMessage -> {
            gistListener?.onMessageShown(action.message)
        }

        is InAppMessagingAction.DismissMessage -> {
            gistListener?.onMessageDismissed(action.message)
        }

        is InAppMessagingAction.EngineAction.MessageLoadingFailed -> {
            gistListener?.onError(action.message)
        }

        is InAppMessagingAction.EngineAction.Tap -> {
            gistListener?.onAction(action.message, action.route, action.action, action.name)
        }
    }
    next(action)
}
