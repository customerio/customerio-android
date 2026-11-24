package io.customer.messaginginapp.gist.presentation.hostwindow

import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.ModalMessageState

/** Feeds the modal slice of the in-app state store to the presenter. */
internal class InAppMessagingManagerModalStateSource(
    private val manager: InAppMessagingManager
) : ModalStateSource {
    override val currentState: ModalPresentationState
        get() = manager.getCurrentState().modalMessageState.toPresentationState()

    override fun subscribe(listener: (ModalPresentationState) -> Unit): () -> Unit {
        val job = manager.subscribeToAttribute(selector = { it.modalMessageState }) { state ->
            listener(state.toPresentationState())
        }
        return { job.cancel() }
    }

    private fun ModalMessageState.toPresentationState(): ModalPresentationState = when (this) {
        is ModalMessageState.Loading -> ModalPresentationState.Loading(message)
        is ModalMessageState.Displayed -> ModalPresentationState.Displayed(message)
        else -> ModalPresentationState.Idle
    }
}

/** Dismisses a message through the store, the same action GistModalActivity dispatches when it is destroyed. */
internal class InAppMessagingManagerModalDismisser(
    private val manager: InAppMessagingManager
) : ModalDismisser {
    override fun dismiss(message: Message) {
        manager.dispatch(InAppMessagingAction.DismissMessage(message = message))
    }
}
