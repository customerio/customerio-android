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
            applyMiddleware()
        )
    }

    fun dispatch(action: InAppMessagingAction) = store.dispatch(action)

    fun subscribe(listener: (InAppMessagingState) -> Unit): () -> Unit {
        return store.subscribe { listener(store.state) }
    }

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
            storeStatFlow
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
            storeStatFlow
                .distinctUntilChanged(areEquivalent)
                .collect { listener(it) }
        }
    }
}
