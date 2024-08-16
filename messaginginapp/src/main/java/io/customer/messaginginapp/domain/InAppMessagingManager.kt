package io.customer.messaginginapp.domain

import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.reduxkotlin.Store

internal object InAppMessagingManager {
    private val store: Store<InAppMessagingState> = InAppMessagingStore.store
    internal val storeStatFlow = MutableStateFlow(store.state)
    internal val scope: CoroutineScope = SDKComponent.scopeProvider.lifecycleListenerScope

    init {
        store.subscribe { storeStatFlow.value = store.state }
    }

    fun dispatch(action: InAppMessagingAction) {
        store.dispatch(action)
    }

    fun getCurrentState() = store.state

    fun subscribe(listener: (InAppMessagingState) -> Unit): () -> Unit {
        return store.subscribe { listener(store.state) }
    }

    fun <T> subscribeToAttribute(
        selector: (InAppMessagingState) -> T,
        listener: (T) -> Unit
    ): Job {
        return scope.launch {
            storeStatFlow
                .map(selector)
                .distinctUntilChanged()
                .collect { listener(it) }
        }
    }
}

internal fun <T1, T2> InAppMessagingManager.subscribeToAttributes(
    selector1: (InAppMessagingState) -> T1,
    selector2: (InAppMessagingState) -> T2,
    listener: (T1, T2) -> Unit
): Job {
    return scope.launch {
        storeStatFlow
            .map { Pair(selector1(it), selector2(it)) }
            .distinctUntilChanged()
            .collect { (v1, v2) -> listener(v1, v2) }
    }
}

internal fun <T1, T2, T3> InAppMessagingManager.subscribeToAttributes(
    selector1: (InAppMessagingState) -> T1,
    selector2: (InAppMessagingState) -> T2,
    selector3: (InAppMessagingState) -> T3,
    listener: (T1, T2, T3) -> Unit
): Job {
    return scope.launch {
        storeStatFlow
            .map { Triple(selector1(it), selector2(it), selector3(it)) }
            .distinctUntilChanged()
            .collect { (v1, v2, v3) -> listener(v1, v2, v3) }
    }
}

internal fun <T1, T2, T3, T4> InAppMessagingManager.subscribeToAttributes(
    selector1: (InAppMessagingState) -> T1,
    selector2: (InAppMessagingState) -> T2,
    selector3: (InAppMessagingState) -> T3,
    selector4: (InAppMessagingState) -> T4,
    listener: (T1, T2, T3, T4) -> Unit
): Job {
    return scope.launch {
        storeStatFlow
            .map { state -> listOf(selector1(state), selector2(state), selector3(state), selector4(state)) }
            .distinctUntilChanged()
            .collect { (v1, v2, v3, v4) -> listener(v1 as T1, v2 as T2, v3 as T3, v4 as T4) }
    }
}
