package io.customer.messaginginapp.state

import android.content.Intent
import com.google.gson.Gson
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.gist.data.model.GistMessageProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GIST_MESSAGE_INTENT
import io.customer.messaginginapp.gist.presentation.GIST_MODAL_POSITION_INTENT
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistModalActivity
import io.customer.sdk.core.di.SDKComponent
import org.reduxkotlin.Store
import org.reduxkotlin.middleware

/**
 * Middleware to log actions and state changes.
 */
internal fun loggerMiddleware() = middleware<InAppMessagingState> { store, next, action ->
    SDKComponent.logger.debug("Store: action: ${action::class.simpleName}: $action")
    SDKComponent.logger.debug("Store: state before reducer: ${store.state}")
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
    if (action.shouldLog) {
        val gistProperties = GistMessageProperties.getGistProperties(action.message)
        if (gistProperties.persistent && action.viaCloseAction || !gistProperties.persistent && action.viaCloseAction) {
            SDKComponent.gistQueue.logView(action.message)
        }
    }
    next(action)
}

private fun handleMessageDisplay(action: InAppMessagingAction.DisplayMessage, next: (Any) -> Any) {
    val gistProperties = GistMessageProperties.getGistProperties(action.message)
    if (!gistProperties.persistent) {
        SDKComponent.gistQueue.logView(action.message)
    }
    next(action)
}

/**
 * Middleware to handle modal message display actions.
 */
internal fun displayModalMessageMiddleware() = middleware<InAppMessagingState> { store, next, action ->
    if (action is InAppMessagingAction.ProcessMessage) {
        handleModalMessageDisplay(store, action, next)
    } else {
        next(action)
    }
}

private fun handleModalMessageDisplay(store: Store<InAppMessagingState>, action: InAppMessagingAction.ProcessMessage, next: (Any) -> Any) {
    if (store.state.currentMessageState !is MessageState.Loaded) {
        val context = store.state.context ?: return
        SDKComponent.logger.debug("Showing message: ${action.message} with position: ${action.position} and context: $context")
        val intent = GistModalActivity.newIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(GIST_MESSAGE_INTENT, Gson().toJson(action.message))
            putExtra(GIST_MODAL_POSITION_INTENT, action.position?.toString())
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
        action is InAppMessagingAction.Initialize -> next(action)
        action is InAppMessagingAction.SetUserIdentifier -> next(action)
        store.state.userId != null -> next(action)
        else -> next(InAppMessagingAction.ReportError("User is not set."))
    }
}

/**
 * Middleware to handle route change actions.
 */
internal fun routeChangeMiddleware() = middleware<InAppMessagingState> { store, next, action ->

    if (action is InAppMessagingAction.SetPageRoute && store.state.currentRoute != action.route) {
        // update the current route
        next(action)

        // cancel the current message if the route rule does not match
        val currentMessage = when (val currentMessageState = store.state.currentMessageState) {
            is MessageState.Loaded -> currentMessageState.message
            is MessageState.Processing -> currentMessageState.message
            else -> null
        }
        val doesCurrentMessageRouteMatch = runCatching {
            val routeRule = currentMessage?.let { message ->
                GistMessageProperties.getGistProperties(message).routeRule
            }
            routeRule == null || routeRule.toRegex().matches(action.route)
        }.getOrNull() ?: true

        // If there is no active message or the message route matches the current route, continue
        if (currentMessage != null && !doesCurrentMessageRouteMatch) {
            store.dispatch(InAppMessagingAction.DismissMessage(message = currentMessage, shouldLog = false))
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
                message.queueId != null && !store.state.shownMessageQueueIds.contains(message.queueId)
            }
            .distinctBy(Message::queueId)
            .sortedWith(compareBy(nullsLast()) { it.priority })

        val notShownMessagesWithProperties = notShownMessages
            .map { message ->
                val properties = GistMessageProperties.getGistProperties(message)
                Pair(message, properties)
            }

        val messageToBeShownWithProperties = notShownMessagesWithProperties.firstOrNull { message ->
            val routeRule = GistMessageProperties.getGistProperties(message.first).routeRule
            val currentRoute = store.state.currentRoute
            when {
                // If the route rule is null, the message should be shown
                routeRule == null -> true
                // otherwise, if current route is null, the message should not be shown because we can't match the route
                currentRoute == null -> false
                // otherwise, match the route rule with the current route
                else -> routeRule.toRegex().matches(currentRoute)
            }
        }

        val isCurrentMessageDisplaying = store.state.currentMessageState is MessageState.Loaded
        val isCurrentMessageBeingProcessed = store.state.currentMessageState is MessageState.Processing

        next(InAppMessagingAction.ProcessMessageQueue(notShownMessages))

        if (messageToBeShownWithProperties != null && !isCurrentMessageDisplaying && !isCurrentMessageBeingProcessed) {
            val message = messageToBeShownWithProperties.first
            val properties = messageToBeShownWithProperties.second

            if (properties.elementId != null) {
                store.dispatch(InAppMessagingAction.EmbedMessage(message, properties.elementId))
            } else {
                store.dispatch(InAppMessagingAction.ProcessMessage(message))
            }
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
internal fun gistListenerMiddleware(gistListener: GistListener?) = middleware<InAppMessagingState> { store, next, action ->
    when (action) {
        is InAppMessagingAction.EmbedMessage -> {
            gistListener?.embedMessage(action.message, action.elementId)
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

        is InAppMessagingAction.EngineAction.Error -> {
            gistListener?.onError(action.message)
        }

        is InAppMessagingAction.EngineAction.Tap -> {
            gistListener?.onAction(action.message, action.route, action.action, action.name)
        }
    }
    next(action)
}
