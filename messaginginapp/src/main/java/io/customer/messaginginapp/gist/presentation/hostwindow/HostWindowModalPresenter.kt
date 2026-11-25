package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.MainThreadPoster

/**
 * Presents modal messages inside the host's own window instead of a separate activity, so the modal
 * never needs a task of its own.
 *
 * Behaves like the GistModalActivity path wherever the two can match:
 *  - A window stays with the activity it opened over. Another activity opened on top covers it (Android
 *    hides a dialog with its activity) and the renderer pauses until that activity is resumed again.
 *  - Only when that activity is destroyed or finishing (rotation, dark mode, a splash screen that
 *    finishes) does the message move to the next resumed activity. That move does not replay the entry
 *    animation or re-dispatch the display event, so the view is logged once
 *    (`shouldDispatchDisplayEvent`, the same switch the inline controller uses on restore).
 *  - When no host is resumed the message waits instead of starting an activity from the background,
 *    which Android blocks (BAL) and which would leave the SDK stuck in Loading.
 *
 * The store is the source of truth. Its updates reach this class through a background collector and a
 * main-thread post, so they can arrive after the next `present` call or be conflated. Every decision
 * therefore reads [ModalStateSource.currentState]: a message is shown only while the store reports it as
 * loading or displayed, and a presentation the store has moved past is ended before the next one starts.
 *
 * All state is confined to the main thread.
 */
internal class HostWindowModalPresenter(
    private val tracker: HostActivityTracker,
    private val windowFactory: ModalWindowFactory,
    private val stateSource: ModalStateSource,
    private val dismisser: ModalDismisser,
    mainThreadPoster: Lazy<MainThreadPoster>,
    private val logger: Logger
) : HostActivityTracker.Listener {
    // Lazy so creating the presenter at module initialization never needs a Looper, which keeps
    // initialization usable in plain JVM tests and before the main looper is touched.
    private val mainThreadPoster by mainThreadPoster

    private class Presentation(val message: Message, val position: MessagePosition) {
        var window: ModalWindow? = null

        /** The SDK reported Displayed. Later windows for this message are carry-overs. */
        var displayed = false
        var cancelSubscription: (() -> Unit)? = null
    }

    private var current: Presentation? = null

    init {
        tracker.listener = this
    }

    /**
     * Takes over presenting [message]. Safe to call from any thread. The store must already report the
     * message as loading when this runs on the main thread, so call it after the load was reduced.
     */
    fun present(message: Message, position: MessagePosition) {
        mainThreadPoster.post { presentOnMain(message, position) }
    }

    override fun onHostResumed(activity: Activity) {
        val presentation = current ?: return
        val window = presentation.window
        if (window != null && !window.isReleased && window.host === activity) {
            window.onHostResumed()
            return
        }
        attach(presentation)
    }

    override fun onHostPaused(activity: Activity) {
        val window = current?.window ?: return
        if (window.host === activity) window.onHostPaused()
    }

    override fun onHostDestroyed(activity: Activity) {
        val presentation = current ?: return
        val window = presentation.window ?: return
        if (window.host !== activity) return
        logger.debug("HostWindowModalPresenter: host ${activity.javaClass.simpleName} destroyed, message moves to the next resumed host")
        window.release()
        presentation.window = null
        // Another activity may already be resumed, for example when a splash screen finishes after
        // starting the main one. Otherwise (rotation) the message waits for the recreated activity.
        attach(presentation)
    }

    private fun presentOnMain(message: Message, position: MessagePosition) {
        current?.let { existing ->
            if (isActive(existing.message)) {
                logger.debug("HostWindowModalPresenter: ${existing.message.queueId} is still active, ignoring ${message.queueId}")
                return
            }
            // The store moved past the previous message (its dismissal can reach us after this call).
            end(existing)
        }
        if (!isActive(message)) {
            // Reset (logout) or a dismissal happened between the load and this call.
            logger.debug("HostWindowModalPresenter: ${message.queueId} is no longer loading, not presenting it")
            return
        }

        val presentation = Presentation(message, position)
        current = presentation
        presentation.cancelSubscription = stateSource.subscribe {
            mainThreadPoster.post { reconcile(presentation) }
        }
        attach(presentation)
    }

    /** Brings [presentation] in line with the store's current state, whatever update triggered the call. */
    private fun reconcile(presentation: Presentation) {
        if (current !== presentation) return
        val state = stateSource.currentState
        when {
            state is ModalPresentationState.Loading && state.message == presentation.message -> Unit

            state is ModalPresentationState.Displayed && state.message == presentation.message -> {
                if (!presentation.displayed) {
                    presentation.displayed = true
                    presentation.window?.onMessageDisplayed(animate = true)
                }
            }

            else -> end(presentation)
        }
    }

    private fun attach(presentation: Presentation) {
        if (current !== presentation) return
        if (!isActive(presentation.message)) {
            end(presentation)
            return
        }
        val existing = presentation.window
        if (existing != null && !existing.isReleased && existing.host.isAlive()) {
            // Stays with the activity it opened over, like GistModalActivity under another activity.
            return
        }
        val host = tracker.resumedActivity
        if (host == null) {
            logger.debug("HostWindowModalPresenter: no resumed host activity, message waits")
            return
        }

        existing?.release()
        presentation.window = null

        // The store can report Displayed before the posted reconcile runs, for example when a relaunch
        // was already queued on the main thread. Reading it here keeps the replacement window from
        // reporting the message a second time, and the late reconcile from animating it in again.
        val state = stateSource.currentState
        if (state is ModalPresentationState.Displayed && state.message == presentation.message) {
            presentation.displayed = true
        }
        val carryOver = presentation.displayed
        logger.debug("HostWindowModalPresenter: attaching to ${host.javaClass.simpleName} (carryOver=$carryOver)")
        val window = windowFactory.create(
            host = host,
            message = presentation.message,
            position = presentation.position,
            shouldDispatchDisplayEvent = !carryOver,
            listener = object : ModalWindowListener {
                override fun onCancelled() = dismisser.dismiss(presentation.message)
            }
        )
        presentation.window = window
        try {
            window.show()
            if (carryOver) window.onMessageDisplayed(animate = false)
        } catch (t: Throwable) {
            logger.error("HostWindowModalPresenter: could not show the modal window: $t", throwable = t)
            window.release()
            presentation.window = null
        }
    }

    /** Ends [presentation]: animates its window out when the message was on screen, removes it otherwise. */
    private fun end(presentation: Presentation) {
        if (current === presentation) current = null
        presentation.cancelSubscription?.invoke()
        presentation.cancelSubscription = null
        val window = presentation.window
        presentation.window = null
        logger.debug("HostWindowModalPresenter: ending ${presentation.message.queueId}, window attached: ${window != null}")
        if (presentation.displayed) window?.dismiss { } else window?.release()
    }

    private fun isActive(message: Message): Boolean = when (val state = stateSource.currentState) {
        is ModalPresentationState.Loading -> state.message == message
        is ModalPresentationState.Displayed -> state.message == message
        ModalPresentationState.Idle -> false
    }

    private fun Activity.isAlive(): Boolean = !isFinishing && !isDestroyed
}
