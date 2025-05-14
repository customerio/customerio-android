package io.customer.messaginginapp.ui.controller

import androidx.annotation.UiThread
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.InlineMessageState
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.InlineInAppMessageViewCallback

@InternalCustomerIOApi
class InlineInAppMessageViewController(
    viewDelegate: InAppHostViewDelegate,
    platformDelegate: InAppPlatformDelegate
) : InAppMessageViewController<InlineInAppMessageViewCallback>(
    type = "Inline",
    platformDelegate = platformDelegate,
    viewDelegate = viewDelegate
) {
    private val elapsedTimer: ElapsedTimer = ElapsedTimer()
    private var contentWidthInDp: Double? = null
    private var contentHeightInDp: Double? = null
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
        viewDelegate.isVisible = false
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
            viewDelegate.post { refreshViewState(state = state) }
        }
    }

    private fun onElementIdChanged() {
        // Since the elementId has been changed, we need to fetch
        // current state to ensure correct message is displayed
        val state = inAppMessagingManager.getCurrentState()
        viewDelegate.post { refreshViewState(state = state) }
    }

    override fun routeLoaded(route: String) {
        super.routeLoaded(route)

        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(message))
        }
    }

    internal fun onDetachedFromWindow() {
        val message = currentMessage ?: return

        if (platformDelegate.shouldDestroyViewOnDetach()) {
            logViewEvent("View detached from window, dismissing inline message view for $elementId ")
            inAppMessagingManager.dispatch(
                InAppMessagingAction.DismissMessage(
                    message = message,
                    shouldLog = false,
                    viaCloseAction = false
                )
            )
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
        stopEngineWebViewLoading()
        viewDelegate.post {
            platformDelegate.animateViewSize(
                widthInPx = null,
                heightInPx = 0,
                duration = null,
                onStart = null,
                onEnd = {
                    currentMessage = null
                    viewDelegate.isVisible = false
                    contentWidthInDp = null
                    contentHeightInDp = null
                    detachAndCleanupEngineWebView()
                    viewCallback?.onNoMessageToDisplay()
                    onComplete()
                }
            )
        }
    }

    @UiThread
    private fun displayMessage(message: Message) {
        elapsedTimer.start("Displaying inline message: ${message.messageId}")
        attachEngineWebView()
        viewCallback?.onLoadingStarted()
        viewDelegate.isVisible = true
        loadMessage(message)
    }

    override fun sizeChanged(width: Double, height: Double) {
        // This is important because the sizeChanged callback is called multiple times which could
        // cause the view animation to be called multiple times and affect visibility and performance.
        if (currentMessage == null || (contentWidthInDp == width && contentHeightInDp == height)) {
            return
        }

        contentWidthInDp = width
        contentHeightInDp = height
        super.sizeChanged(width, height)
        elapsedTimer.end()
    }

    override fun onWebViewSizeUpdated(widthInPx: Int, heightInPx: Int) {
        super.onWebViewSizeUpdated(widthInPx, heightInPx)
        viewDelegate.post {
            platformDelegate.animateViewSize(
                widthInPx = widthInPx,
                heightInPx = heightInPx,
                duration = null,
                onStart = {
                    engineWebViewDelegate?.let { delegate ->
                        delegate.setAlpha(1.0F)
                        delegate.bringToFront()
                    }
                    viewCallback?.onLoadingFinished()
                },
                onEnd = null
            )
        }
    }

    /**
     * Cleans up EngineWebView and releases its resources.
     * This method should be called when EngineWebView instance is no longer needed.
     */
    @UiThread
    private fun detachAndCleanupEngineWebView() {
        logViewEvent("Detaching and cleaning up EngineWebView")
        val view = engineWebViewDelegate
        detachEngineWebView()
        view?.releaseResources()
    }
}
