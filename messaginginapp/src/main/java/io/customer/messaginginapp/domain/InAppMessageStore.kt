package io.customer.messaginginapp.domain

import android.content.Intent
import com.google.gson.Gson
import io.customer.messaginginapp.di.gistQueue
import io.customer.messaginginapp.gist.data.model.GistMessageProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GIST_MESSAGE_INTENT
import io.customer.messaginginapp.gist.presentation.GIST_MODAL_POSITION_INTENT
import io.customer.messaginginapp.gist.presentation.GistModalActivity
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import org.reduxkotlin.Store
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.middleware
import org.reduxkotlin.threadsafe.createThreadSafeStore

internal object InAppMessagingStore {

    private val logger = SDKComponent.logger
    val store: Store<InAppMessagingState> = createThreadSafeStore(
        reducer = inAppMessagingReducer,
        preloadedState = InAppMessagingState(),
        applyMiddleware(
            loggerMiddleware(logger),
            // needs to be first middleware to ensure that the user is set before processing any other actions
            onUserChange(),
            onRouteChange(),
            onShowModalMessageChanges(),
            onDismissMessage(),
            processMessages(),
            errorLogger()
        )
    )
}

fun loggerMiddleware(logger: Logger) = middleware<InAppMessagingState> { store, next, action ->
    logger.debug("Store: action: ${action::class.simpleName}: $action")
    logger.debug("Store: state before reducer: ${store.state}")
    next(action)
}

fun errorLogger() = middleware<InAppMessagingState> { _, next, action ->
    if (action is InAppMessagingAction.Error) {
        SDKComponent.logger.error("Error: ${action.message}")
    }
    next(action)
}

fun onDismissMessage() = middleware<InAppMessagingState> { _, next, action ->
    when (action) {
        is InAppMessagingAction.DismissMessage -> {
            if (!action.shouldLog) {
                next(action)
            } else {
                val gistProperties = GistMessageProperties.getGistProperties(action.message)
                if (gistProperties.persistent && action.viaCloseAction) {
                    SDKComponent.gistQueue.logView(action.message)
                } else if (!gistProperties.persistent && action.viaCloseAction) {
                    SDKComponent.gistQueue.logView(action.message)
                }
            }
            next(action)
        }

        is InAppMessagingAction.MakeMessageVisible -> {
            val gistProperties = GistMessageProperties.getGistProperties(action.message)
            if (!gistProperties.persistent) {
                SDKComponent.gistQueue.logView(action.message)
            }
            next(action)
        }

        else -> {
            next(action)
        }
    }
}

fun onShowModalMessageChanges() = middleware<InAppMessagingState> { store, next, action ->
    if (action is InAppMessagingAction.ShowModalMessage) {
        if (store.state.currentMessageState !is MessageState.Loaded) {
            val context = store.state.context ?: return@middleware next(InAppMessagingAction.Error("Context is null"))
            SDKComponent.logger.debug("Showing message: ${action.message} with position: ${action.position} and context: $context")
            val intent = GistModalActivity.newIntent(context)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.putExtra(GIST_MESSAGE_INTENT, Gson().toJson(action.message))
            intent.putExtra(GIST_MODAL_POSITION_INTENT, action.position?.toString())
            context.startActivity(intent)
            next(action)
        } else {
            next(InAppMessagingAction.Error("A message is already being shown or cancelled"))
        }
    } else {
        next(action)
    }
}

fun onUserChange() = middleware<InAppMessagingState> { store, next, action ->
    if (action is InAppMessagingAction.Initialize) {
        next(action)
    } else if (action is InAppMessagingAction.SetUser) {
        next(action)
    } else if (store.state.userId != null) {
        next(action)
    } else {
        next(InAppMessagingAction.Error("User is not set."))
    }
}

fun onRouteChange() = middleware<InAppMessagingState> { store, next, action ->

    if (action is InAppMessagingAction.SetCurrentRoute && store.state.currentRoute != action.route) {
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
        store.dispatch(InAppMessagingAction.ProcessMessages(store.state.messagesInQueue.toList()))
    } else {
        next(action)
    }
}

fun processMessages() = middleware<InAppMessagingState> { store, next, action ->
    if (action is InAppMessagingAction.ProcessMessages) {
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

        next(InAppMessagingAction.ProcessMessages(notShownMessages))

        if (messageToBeShownWithProperties != null && !isCurrentMessageDisplaying && !isCurrentMessageBeingProcessed) {
            val message = messageToBeShownWithProperties.first
            val properties = messageToBeShownWithProperties.second

            next(InAppMessagingAction.LoadMessage(message))

            if (properties.elementId != null) {
                store.dispatch(InAppMessagingAction.EmbedMessage(message, properties.elementId))
            } else {
                store.dispatch(InAppMessagingAction.ShowModalMessage(message))
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
