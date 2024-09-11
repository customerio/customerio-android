package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.reduxkotlin.Store
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.threadsafe.createThreadSafeStore

/**
 * Manages the state and actions for in-app messaging.
 *
 * @property listener An optional listener for Gist events.
 */
data class InAppMessagingManager(val listener: GistListener? = null) {
    private val store: Store<InAppMessagingState> = createStore()
    private val storeStateFlow = MutableStateFlow(store.state)
    private val scope: CoroutineScope = SDKComponent.scopeProvider.inAppLifecycleScope

    init {
        store.subscribe {
            storeStateFlow.value = store.state
        }
    }

    private fun createStore(): Store<InAppMessagingState> {
        return createThreadSafeStore(
            reducer = inAppMessagingReducer,
            preloadedState = InAppMessagingState(),
            applyMiddleware(
                loggerMiddleware(),
                // needs to be first middleware to ensure that the user is set before processing any other actions
                userChangeMiddleware(),
                routeChangeMiddleware(),
                displayModalMessageMiddleware(),
                gistLoggingMessageMiddleware(),
                processMessages(),
                errorLoggerMiddleware(),
                gistListenerMiddleware(listener)
            )
        )
    }

    /**
     * Dispatches an action to the store.
     *
     * @param action The action to dispatch.
     */
    fun dispatch(action: InAppMessagingAction) = store.dispatch(action)

    /**
     * Subscribes to store updates.
     *
     * @param listener A listener to be called with the updated state.
     * @return A function to unsubscribe from the updates.
     */
    fun subscribe(listener: (InAppMessagingState) -> Unit): () -> Unit {
        return store.subscribe { listener(store.state) }
    }

    /**
     * Gets the current state of the store.
     *
     * @return The current state.
     */
    fun getCurrentState() = store.state

    /**
     * Subscribes to a specific attribute of the state.
     * @param selector A function that selects a specific attribute of the state.
     * @param areEquivalent A function that determines if two values are equivalent.
     * @param listener A function that is called when the attribute changes.
     */
    fun <T> subscribeToAttribute(
        selector: (InAppMessagingState) -> T,
        areEquivalent: (old: T, new: T) -> Boolean = { old, new -> old == new },
        listener: (T) -> Unit
    ): Job {
        return scope.launch {
            storeStateFlow
                .map(selector)
                .distinctUntilChanged(areEquivalent)
                .collect {
                    listener(it)
                }
        }
    }

    /**
     * Subscribes to the state.
     * @param areEquivalent A function that determines if two states are equivalent.
     * @param listener A function that is called when the state changes.
     */
    fun subscribeToState(
        areEquivalent: (old: InAppMessagingState, new: InAppMessagingState) -> Boolean = { old, new -> old == new },
        listener: (InAppMessagingState) -> Unit
    ): Job {
        return scope.launch {
            storeStateFlow
                .distinctUntilChanged(areEquivalent)
                .collect { listener(it) }
        }
    }
}
