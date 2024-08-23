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
            applyMiddleware(
                loggerMiddleware(),
                // needs to be first middleware to ensure that the user is set before processing any other actions
                userChangeMiddleware(),
                routeChangeMiddleware(),
                modalMessageMiddleware(),
                dismissMessageMiddleware(),
                processMessages(),
                errorLoggerMiddleware(),
                gistListenerMiddleware(listener)
            )
        )
    }

    fun dispatch(action: InAppMessagingAction) = store.dispatch(action)

    fun subscribe(listener: (InAppMessagingState) -> Unit): () -> Unit {
        return store.subscribe { listener(store.state) }
    }

    fun getCurrentState() = store.state

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
