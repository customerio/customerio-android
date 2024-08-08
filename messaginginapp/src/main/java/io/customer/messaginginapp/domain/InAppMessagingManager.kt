package io.customer.messaginginapp.domain

import io.customer.sdk.core.util.Logger
import org.reduxkotlin.Store

class InAppMessagingManager(private val logger: Logger) {
    private val store: Store<InAppMessagingState> = createInAppMessagingStore(logger)

    fun dispatch(action: InAppMessagingAction) {
        store.dispatch(action)
    }

    fun getCurrentState() = store.state

    fun subscribe(listener: (InAppMessagingState) -> Unit): () -> Unit {
        return store.subscribe { listener(store.state) }
    }
}
