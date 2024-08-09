package io.customer.messaginginapp.domain

import org.reduxkotlin.Store

internal object InAppMessagingManager {
    private val store: Store<InAppMessagingState> = InAppMessagingStore.store

    fun dispatch(action: InAppMessagingAction) {
        store.dispatch(action)
    }

    fun getCurrentState() = store.state

    fun subscribe(listener: (InAppMessagingState) -> Unit): () -> Unit {
        return store.subscribe { listener(store.state) }
    }
}
