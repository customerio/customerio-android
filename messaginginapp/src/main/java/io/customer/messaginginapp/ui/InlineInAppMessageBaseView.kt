package io.customer.messaginginapp.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.annotation.UiThread
import androidx.core.view.updateLayoutParams
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.InlineMessageState

@Suppress("MemberVisibilityCanBePrivate")
abstract class InlineInAppMessageBaseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : InAppMessageBaseView(context, attrs, defStyleAttr, defStyleRes) {
    internal var viewListener: InlineInAppMessageViewListener? = null

    protected val elapsedTimer: ElapsedTimer = ElapsedTimer()
    protected var contentWidthInDp: Double? = null
    protected var contentHeightInDp: Double? = null

    var elementId: String? = null
        set(value) {
            val oldValue = field
            field = value
            if (oldValue != value) {
                logViewEvent("Element ID changed from $oldValue to $value")
                // Fetch current state to ensure we have latest message for given element ID
                onElementIdChanged()
            }
        }

    init {
        visibility = GONE
        subscribeToStore()
    }

    private fun subscribeToStore() {
        inAppMessagingManager.subscribeToState(
            areEquivalent = { oldState, newState ->
                val viewElementId = this.elementId ?: return@subscribeToState true

                val oldMessage = oldState.queuedInlineMessagesState.getMessage(viewElementId)
                val newMessage = newState.queuedInlineMessagesState.getMessage(viewElementId)
                return@subscribeToState oldMessage == newMessage
            }
        ) { state ->
            post { refreshViewState(state = state) }
        }
    }

    protected open fun onElementIdChanged() {
        // Since the elementId has been changed, we need to fetch
        // current state to ensure correct message is displayed
        val state = inAppMessagingManager.getCurrentState()
        post { refreshViewState(state = state) }
    }

    override fun routeLoaded(route: String) {
        super.routeLoaded(route)

        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(message))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val message = currentMessage ?: return

        if (shouldDestroyOnDetach()) {
            logViewEvent("View detached from window, dismissing inline message view for $elementId ")
            inAppMessagingManager.dispatch(InAppMessagingAction.DismissMessage(message = message, shouldLog = false, viaCloseAction = false))
        } else {
            logViewEvent("Skipping destroy for inline message view for $elementId — likely config change or temporary detach")
        }
    }

    @UiThread
    private fun refreshViewState(state: InAppMessagingState) {
        val viewElementId = elementId ?: return
        val inlineMessageState = state.queuedInlineMessagesState.getMessage(viewElementId) ?: return

        when (inlineMessageState) {
            is InlineMessageState.ReadyToEmbed -> embedMessage(message = inlineMessageState.message)
            is InlineMessageState.Dismissed -> dismissMessage(message = inlineMessageState.message) {}
            is InlineMessageState.Embedded -> {
                // The message is already embedded, but we are not displaying it. This can happen
                // when the message was already displayed in UI but the view was recreated.
                // In this case, we need to show the message again.
                if (currentMessage == null) {
                    logViewEvent("View recreated, embedding inline message again: ${inlineMessageState.message.messageId}")
                    embedMessage(message = inlineMessageState.message)
                }
            }
        }
    }

    @UiThread
    private fun embedMessage(message: Message) {
        logViewEvent("Loading inline message: ${message.messageId}")

        val oldMessage = currentMessage
        // If no message is currently displayed, show the new one
        if (oldMessage == null) {
            displayMessage(message)
            return
        }

        // Else, dismiss the old one before showing the new one
        dismissMessage(oldMessage) {
            displayMessage(message)
        }
    }

    @UiThread
    private fun dismissMessage(message: Message, onComplete: (() -> Unit)) {
        logViewEvent("Dismissing inline message: ${message.messageId}")
        hideView {
            currentMessage = null
            onComplete()
        }
    }

    @UiThread
    private fun displayMessage(message: Message) {
        elapsedTimer.start("Displaying inline message: ${message.messageId}")
        attachEngineWebView()
        viewListener?.onLoadingStarted()
        visibility = VISIBLE
        setup(message)
    }

    override fun sizeChanged(width: Double, height: Double) {
        // This is important because the sizeChanged callback is called multiple times which could
        // cause the view animation to be called multiple times and affect visibility and performance.
        if (currentMessage == null || (contentWidthInDp == width && contentHeightInDp == height)) {
            return
        }

        contentWidthInDp = width
        contentHeightInDp = height
        post { showMessageView(width, height) }
        elapsedTimer.end()
    }

    /**
     * Expands the view to the specified width and height (in dp), with animation.
     * This updates the size of the inline message view, notifies listeners,
     * and ensures the content (e.g. WebView) is shown on top after hiding the loader.
     */
    @UiThread
    private fun showMessageView(widthInDp: Double, heightInDp: Double) {
        logViewEvent("Updating inline message size: $widthInDp x $heightInDp")

        val widthInPx = dpToPixels(widthInDp)
        val heightInPx = dpToPixels(heightInDp)
        viewListener?.onViewSizeChanged(widthInPx, heightInPx)

        animateViewSize(
            widthInPx = widthInPx,
            heightInPx = heightInPx,
            onStart = {
                engineWebView?.let { view ->
                    view.alpha = 1.0F
                    view.bringToFront()
                }
                viewListener?.onLoadingFinished()
            },
            onEnd = {}
        )
    }

    /**
     * Cleans up EngineWebView and releases its resources.
     * This method should be called when EngineWebView instance is no longer needed.
     */
    @UiThread
    internal fun detachAndCleanupEngineWebView() {
        val view = engineWebView ?: return

        detachEngineWebView()
        view.releaseResources()
    }

    /**
     * Collapses the view by animating its height to zero and hiding internal elements.
     * This is an asynchronous operation when [shouldAnimate] is true.
     * Use [onComplete] to perform any actions after the collapse completes.
     */
    @UiThread
    internal fun hideView(
        shouldAnimate: Boolean = true,
        onComplete: (() -> Unit)? = null
    ) {
        val completionHandler: () -> Unit = {
            visibility = GONE
            engineWebView?.alpha = 0F
            contentWidthInDp = null
            contentHeightInDp = null
            detachAndCleanupEngineWebView()
            viewListener?.onNoMessageToDisplay()
            onComplete?.invoke()
        }

        logViewEvent("Hiding inline message view with animation: $shouldAnimate")
        stopLoading()
        if (shouldAnimate) {
            animateViewSize(
                heightInPx = 0,
                onEnd = completionHandler
            )
        } else {
            updateLayoutParams {
                height = 0
            }
            completionHandler()
        }
    }
}
