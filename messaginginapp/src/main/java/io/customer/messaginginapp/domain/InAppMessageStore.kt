package io.customer.messaginginapp.domain

import io.customer.messaginginapp.gist.data.listeners.Queue
import io.customer.messaginginapp.gist.data.model.GistMessageProperties
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import java.util.Timer
import kotlin.concurrent.timer
import org.reduxkotlin.Dispatcher
import org.reduxkotlin.Middleware
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
            checkActiveMessageOnRouteChange(),
            GistQueueMiddleware(logger)
        )
    )
}

fun loggerMiddleware(logger: Logger) = middleware<InAppMessagingState> { store, next, action ->
    val result = next(action)
    logger.debug("DISPATCH action: ${action::class.simpleName}: $action")
    logger.debug("next state: ${store.state}")
    result
}

fun checkActiveMessageOnRouteChange() = middleware<InAppMessagingState> { store, next, action ->
    if (action is InAppMessagingAction.SetCurrentRoute) {
        val currentMessage = store.state.currentMessage
        val isRouteMatch = runCatching {
            val routeRule = currentMessage?.let { message ->
                GistMessageProperties.getGistProperties(message).routeRule
            }
            routeRule == null || routeRule.toRegex().matches(action.route)
        }.getOrNull() ?: true

        if (currentMessage != null && !isRouteMatch) {
            next(action)
        } else {
            next(InAppMessagingAction.SetCurrentRoute(action.route))
            // handleGistCancelled(currentMessage)
        }
    }
}

class GistQueueMiddleware(private val logger: Logger) : Middleware<InAppMessagingState> {

    private var timer: Timer? = null
    private var gistQueue: Queue = Queue()

    override fun invoke(store: Store<InAppMessagingState>): (Dispatcher) -> Dispatcher {
        return { next ->
            { action ->
                val result = next(action) // Process the action and let the reducer update the state

                if (action is InAppMessagingAction.Reset) {
                    // Initialize the Gist SDK with the siteId and dataCenter
                    gistQueue.clearPrefs(action.context)
                }

                // Now check the updated state after the action has been processed
                val currentState = store.state
                if (currentState.isInitialized && currentState.isAppInForeground && currentState.userId != null) {
                    // Start polling if the app is initialized and in the foreground
                    if (timer == null) {
                        fetchInAppMessages(currentState.pollInterval)
                    }
                } else {
                    // Optionally stop polling if the app goes to the background or is uninitialized
                    logger.debug("Stopping polling")
                    timer?.cancel()
                    timer = null
                }

                result // Return the result of next(action)
            }
        }
    }

    private fun fetchInAppMessages(duration: Long) {
        logger.debug("Starting polling")
        timer = timer(name = "GistPolling", period = duration) {
            gistQueue.fetchUserMessages()
        }
    }
}
