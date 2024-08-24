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
    internal val storeStatFlow = MutableStateFlow(store.state)
    internal val scope: CoroutineScope = SDKComponent.scopeProvider.lifecycleListenerScope

    init {
        store.subscribe {
            storeStatFlow.value = store.state
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
     * Subscribes to changes in a specific attribute of the state.
     *
     * @param selector A function to select the attribute from the state.
     * @param areEquivalent A function to compare the old and new values of the attribute.
     * @param listener A listener to be called with the updated attribute value.
     * @return A Job representing the subscription.
     */
    fun <T> subscribeToAttribute(
        selector: (InAppMessagingState) -> T,
        areEquivalent: (old: T, new: T) -> Boolean = { old, new -> old == new },
        listener: (T) -> Unit
    ): Job {
        return scope.launch {
            storeStatFlow
                .map(selector)
                .distinctUntilChanged(areEquivalent)
                .collect {
                    listener(it)
                }
        }
    }
}

/**
 * Subscribes to changes in two specific attributes of the state.
 *
 * @param selector1 A function to select the first attribute from the state.
 * @param selector2 A function to select the second attribute from the state.
 * @param areEquivalent A function to compare the old and new values of the attributes.
 * @param listener A listener to be called with the updated attribute values.
 * @return A Job representing the subscription.
 */
internal fun <T1, T2> InAppMessagingManager.subscribeToAttributes(
    selector1: (InAppMessagingState) -> T1,
    selector2: (InAppMessagingState) -> T2,
    areEquivalent: (old: Pair<T1, T2>, new: Pair<T1, T2>) -> Boolean = { old, new -> old == new },
    listener: (T1, T2) -> Unit
): Job {
    return scope.launch {
        storeStatFlow
            .map { Pair(selector1(it), selector2(it)) }
            .distinctUntilChanged(areEquivalent)
            .collect { (v1, v2) -> listener(v1, v2) }
    }
}

/**
 * Subscribes to changes in three specific attributes of the state.
 *
 * @param selector1 A function to select the first attribute from the state.
 * @param selector2 A function to select the second attribute from the state.
 * @param selector3 A function to select the third attribute from the state.
 * @param areEquivalent A function to compare the old and new values of the attributes.
 * @param listener A listener to be called with the updated attribute values.
 * @return A Job representing the subscription.
 */
internal fun <T1, T2, T3> InAppMessagingManager.subscribeToAttributes(
    selector1: (InAppMessagingState) -> T1,
    selector2: (InAppMessagingState) -> T2,
    selector3: (InAppMessagingState) -> T3,
    areEquivalent: (old: Triple<T1, T2, T3>, new: Triple<T1, T2, T3>) -> Boolean = { old, new -> old == new },
    listener: (T1, T2, T3) -> Unit
): Job {
    return scope.launch {
        storeStatFlow
            .map { Triple(selector1(it), selector2(it), selector3(it)) }
            .distinctUntilChanged(areEquivalent)
            .collect { (v1, v2, v3) -> listener(v1, v2, v3) }
    }
}
