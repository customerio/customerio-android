package io.customer.messaginginapp.state

import android.content.Intent
import com.google.gson.Gson
import io.customer.messaginginapp.di.anonymousMessageManager
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.di.gistSdk
import io.customer.messaginginapp.di.inAppSseLogger
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.isMessageAnonymous
import io.customer.messaginginapp.gist.data.model.matchesRoute
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistModalActivity
import io.customer.messaginginapp.gist.utilities.ModalMessageParser
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import org.reduxkotlin.Store
import org.reduxkotlin.middleware

/**
 * Middleware to log actions and state changes.
 */
internal fun loggerMiddleware() = middleware<InAppMessagingState> { store, next, action ->
    val logger = SDKComponent.logger
    logger.debug("Store: action: $action")
    logger.debug("Store: state before reducer: ${store.state}")

    // continue passing the original action down the middleware chain
    next(action)
}

/**
 * Middleware to log errors.
 */
internal fun errorLoggerMiddleware() = middleware<InAppMessagingState> { _, next, action ->
    val logger = SDKComponent.logger
    if (action is InAppMessagingAction.ReportError) {
        logger.error("Error: ${action.message}")
    }
    next(action)
}

/**
 * Middleware to handle gist logging for message.
 */
internal fun gistLoggingMessageMiddleware() = middleware<InAppMessagingState> { store, next, action ->
    val logger = SDKComponent.logger
    when (action) {
        is InAppMessagingAction.DismissMessage -> handleMessageDismissal(logger, store, action, next)
        is InAppMessagingAction.DisplayMessage -> handleMessageDisplay(logger, action, next)
        else -> next(action)
    }
}

private fun handleMessageDismissal(logger: Logger, store: Store<InAppMessagingState>, action: InAppMessagingAction.DismissMessage, next: (Any) -> Any) {
    // Handle anonymous message dismissal
    if (action.message.isMessageAnonymous() && action.message.queueId != null) {
        logger.debug("Anonymous message dismissed: ${action.message.queueId}")
        try {
            SDKComponent.anonymousMessageManager.markAnonymousAsDismissed(action.message.queueId)
        } catch (e: Exception) {
            logger.debug("Failed to mark anonymous message as dismissed: ${e.message}")
        }
    }
    // Log message close only if message should be tracked as shown on dismiss action
    if (action.shouldMarkMessageAsShown()) {
        logger.debug("Persistent message dismissed, logging view for message: ${action.message}, shouldLog: ${action.shouldLog}, viaCloseAction: ${action.viaCloseAction}")
        SDKComponent.gistQueue.logView(action.message)
        logger.debug("Fetching in-app messages after message dismissal")

        // When SSE is enabled, this won't fetch messages
        SDKComponent.gistSdk.fetchInAppMessages()
    } else {
        logger.debug("Message dismissed, not logging view for message: ${action.message}, shouldLog: ${action.shouldLog}, viaCloseAction: ${action.viaCloseAction}")
    }

    // Process the DismissMessage action first so the reducer can update shownMessageQueueIds
    // This ensures the dismissed message is properly marked as shown before processing the queue
    next(action)

    // After the dismissal is processed, dispatch ProcessMessageQueue to show the next message
    // The dismissed message will be filtered out by processMessages() since its queueId is now in shownMessageQueueIds
    if (store.state.shouldUseSse) {
        SDKComponent.inAppSseLogger.logTryDisplayNextMessageAfterDismissal()
        store.dispatch(InAppMessagingAction.ProcessMessageQueue(store.state.messagesInQueue.toList()))
    }
}

private fun handleMessageDisplay(logger: Logger, action: InAppMessagingAction.DisplayMessage, next: (Any) -> Any) {
    // Handle anonymous message tracking
    if (action.message.isMessageAnonymous() && action.message.queueId != null) {
        logger.debug("Anonymous message displayed: ${action.message.queueId}")
        SDKComponent.anonymousMessageManager.markAnonymousAsSeen(action.message.queueId)
    }
    // Log message view only if message should be tracked as shown on display action
    if (action.shouldMarkMessageAsShown()) {
        logger.debug("Message shown, logging view for message: ${action.message}")
        SDKComponent.gistQueue.logView(action.message)
    } else {
        logger.debug("Persistent message shown, not logging view for message: ${action.message}")
    }
    next(action)
}

/**
 * Middleware to handle modal message display actions.
 */
internal fun displayModalMessageMiddleware() = middleware { store, next, action ->
    val logger = SDKComponent.logger
    if (action is InAppMessagingAction.LoadMessage) {
        handleModalMessageDisplay(logger, store, action, next)
    } else {
        next(action)
    }
}

private fun handleModalMessageDisplay(logger: Logger, store: Store<InAppMessagingState>, action: InAppMessagingAction.LoadMessage, next: (Any) -> Any) {
    if (store.state.modalMessageState !is ModalMessageState.Displayed) {
        val context = SDKComponent.android().applicationContext
        logger.debug("Showing message: ${action.message} with position: ${action.position} and context: $context")
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
 * Middleware to handle route change actions.
 */
internal fun routeChangeMiddleware() = middleware<InAppMessagingState> { store, next, action ->
    val logger = SDKComponent.logger

    if (action is InAppMessagingAction.SetPageRoute) {
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
                logger.debug("Dismissing message: ${currentMessage.queueId} because route does not match current route: ${action.route}")
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
            for (message in action.messages) {
                message.embeddedElementId?.let { elementId ->
                    gistListener?.embedMessage(message, elementId)
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
