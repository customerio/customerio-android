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

/**
 * Dismisses a message through the store, the same action GistModalActivity dispatches when it is destroyed.
 *
 * Like GistModalActivity.onDestroy, it dispatches only while the store still shows [message] as Displayed.
 * A late Back, after the store moved on to the next message or already dismissed this one, would otherwise
 * overwrite the next message's state and report the dismissal twice. The window takes no input while it
 * loads, so Back can only come from a displayed message.
 */
internal class InAppMessagingManagerModalDismisser(
    private val manager: InAppMessagingManager
) : ModalDismisser {
    override fun dismiss(message: Message) {
        val state = manager.getCurrentState().modalMessageState
        if (state !is ModalMessageState.Displayed || state.message != message) return
        manager.dispatch(InAppMessagingAction.DismissMessage(message = message))
    }
}
