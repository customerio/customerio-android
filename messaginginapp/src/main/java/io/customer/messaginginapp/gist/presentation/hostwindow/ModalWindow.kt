package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition

/**
 * A modal message rendered inside a host activity's window. All members are called on the main thread.
 */
internal interface ModalWindow {
    /** The host activity this window is attached to. */
    val host: Activity

    /** True once the window was removed, by [release], by dismissal or by the system. A released window is never shown again. */
    val isReleased: Boolean

    /** Attaches the window and starts loading the message. The window stays invisible and untouchable until [onMessageDisplayed]. */
    fun show()

    /** The SDK reports the message as displayed. [animate] is false when the message is carried over from another host. */
    fun onMessageDisplayed(animate: Boolean)

    /**
     * Stops the renderer and blocks input, animates the window out, removes it and then calls [onDismissed].
     * A call made while the window is already animating out joins that animation, so each callback runs once.
     */
    fun dismiss(onDismissed: () -> Unit)

    /** Removes the window immediately without animation. Safe to call more than once. */
    fun release()

    /**
     * The host left the foreground (Home, or another activity opened on top). Android hides the dialog
     * with its host; this pauses the renderer the way GistModalActivity's own pause does, and keeps
     * it paused until [onHostResumed].
     */
    fun onHostPaused()

    /** The host is in the foreground again. Resumes the renderer. */
    fun onHostResumed()
}

internal interface ModalWindowListener {
    /** The user cancelled the modal, for example with the back button. */
    fun onCancelled()
}

internal fun interface ModalWindowFactory {
    /**
     * @param shouldDispatchDisplayEvent false when the message was already reported as displayed, so
     * moving it to another host does not log a second view.
     */
    fun create(
        host: Activity,
        message: Message,
        position: MessagePosition,
        shouldDispatchDisplayEvent: Boolean,
        listener: ModalWindowListener
    ): ModalWindow
}

/** The slice of the modal state the presenter reacts to. */
internal sealed class ModalPresentationState {
    object Idle : ModalPresentationState()
    data class Loading(val message: Message) : ModalPresentationState()
    data class Displayed(val message: Message) : ModalPresentationState()
}

internal interface ModalStateSource {
    /**
     * The modal state right now. Updates from [subscribe] arrive later and can be conflated, so a
     * decision that must not act on a stale message reads this instead.
     */
    val currentState: ModalPresentationState

    /** Subscribes to modal state changes. The current state is delivered first. Returns a function that cancels the subscription. */
    fun subscribe(listener: (ModalPresentationState) -> Unit): () -> Unit
}

internal fun interface ModalDismisser {
    fun dismiss(message: Message)
}
