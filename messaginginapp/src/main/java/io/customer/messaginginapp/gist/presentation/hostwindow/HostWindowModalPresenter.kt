package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.MainThreadPoster

/**
 * Presents modal messages inside the host's own window instead of a separate activity, so the modal
 * never needs a task of its own. This is how Braze, Firebase In-App Messaging, OneSignal and Iterable
 * render in-app messages.
 *
 * The presenter owns the message independently of any window, following the same practices as those SDKs:
 *  - A window is bound to the resumed host activity. When the host changes or is destroyed (navigation,
 *    rotation, dark mode) the window is released and the message carries over to the next resumed host
 *    without replaying the entry animation (Braze `carryoverInAppMessage`, Firebase unbind/rebind).
 *  - A carried-over message does not re-dispatch the display event, so the view is logged once
 *    (`shouldDispatchDisplayEvent`, the same switch the inline controller uses on restore).
 *  - When no host is resumed the message waits instead of starting an activity from the background,
 *    which Android blocks (BAL) and which would leave the SDK stuck in Loading.
 *  - The active slot is freed as soon as dismissal starts. The SDK dispatches the next load in the
 *    same millisecond as the dismissal, long before the exit animation ends.
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

        /** The SDK reported Loading or Displayed for this message. Only then can a state change mean "dismissed". */
        var seenActive = false

        /** The SDK reported Displayed. Later windows for this message are carry-overs. */
        var displayed = false
        var cancelSubscription: (() -> Unit)? = null
    }

    private var current: Presentation? = null

    init {
        tracker.listener = this
    }

    /** Takes over presenting [message]. Safe to call from any thread. */
    fun present(message: Message, position: MessagePosition) {
        mainThreadPoster.post { presentOnMain(message, position) }
    }

    override fun onHostResumed(activity: Activity) {
        current?.let { attach(it) }
    }

    override fun onHostDestroyed(activity: Activity) {
        val presentation = current ?: return
        val window = presentation.window ?: return
        if (window.host !== activity) return
        logger.debug("HostWindowModalPresenter: host ${activity.javaClass.simpleName} destroyed, message waits for the next host")
        window.release()
        presentation.window = null
    }

    private fun presentOnMain(message: Message, position: MessagePosition) {
        current?.let { existing ->
            if (existing.displayed) {
                logger.debug("HostWindowModalPresenter: a message is already displayed, ignoring")
                return
            }
            // A load that never reached the screen (no host yet) is replaced by the newer one.
            discard(existing)
        }

        val presentation = Presentation(message, position)
        current = presentation
        presentation.cancelSubscription = stateSource.subscribe { state ->
            mainThreadPoster.post { onState(presentation, state) }
        }
        attach(presentation)
    }

    private fun onState(presentation: Presentation, state: ModalPresentationState) {
        if (current !== presentation) return
        when {
            state is ModalPresentationState.Loading && state.message == presentation.message -> {
                presentation.seenActive = true
            }

            state is ModalPresentationState.Displayed && state.message == presentation.message -> {
                presentation.seenActive = true
                if (!presentation.displayed) {
                    presentation.displayed = true
                    presentation.window?.onMessageDisplayed(animate = true)
                }
            }

            // Dismissed, reset or replaced after this message was active. Ignoring states seen before
            // it became active avoids dismissing a message because the previous one was dismissed.
            presentation.seenActive -> dismiss(presentation)
        }
    }

    private fun attach(presentation: Presentation) {
        val host = tracker.resumedActivity
        if (host == null) {
            logger.debug("HostWindowModalPresenter: no resumed host activity, message waits")
            return
        }
        val existing = presentation.window
        if (existing != null && existing.host === host && !existing.isReleased) return

        existing?.release()
        presentation.window = null

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

    private fun dismiss(presentation: Presentation) {
        if (current === presentation) current = null
        presentation.cancelSubscription?.invoke()
        presentation.cancelSubscription = null
        val window = presentation.window
        presentation.window = null
        logger.debug("HostWindowModalPresenter: dismissing message, window attached: ${window != null}")
        window?.dismiss { }
    }

    private fun discard(presentation: Presentation) {
        if (current === presentation) current = null
        presentation.cancelSubscription?.invoke()
        presentation.cancelSubscription = null
        presentation.window?.release()
        presentation.window = null
    }
}
