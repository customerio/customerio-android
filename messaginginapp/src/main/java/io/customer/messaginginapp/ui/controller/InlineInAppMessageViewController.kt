package io.customer.messaginginapp.ui.controller

import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.matchesRoute
import io.customer.messaginginapp.gist.utilities.ElapsedTimer
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.state.InlineMessageState
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.InlineInAppMessageViewCallback
import kotlinx.coroutines.Job

internal class InlineInAppMessageViewController
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal constructor(
    viewDelegate: InAppHostViewDelegate,
    platformDelegate: InAppPlatformDelegate,
    private val elapsedTimer: ElapsedTimer
) : InAppMessageViewController<InlineInAppMessageViewCallback>(
    type = "Inline",
    platformDelegate = platformDelegate,
    viewDelegate = viewDelegate
) {
    internal constructor(
        viewDelegate: InAppHostViewDelegate,
        platformDelegate: InAppPlatformDelegate
    ) : this(
        viewDelegate = viewDelegate,
        platformDelegate = platformDelegate,
        elapsedTimer = ElapsedTimer()
    )

    @ThreadSafeProperty("Accessed from UI thread and background state subscriptions")
    internal var elementId: String? by threadSafeWithNotification { old, new ->
        if (old != new) {
            logViewEvent("Element ID changed from $old to $new")
            // Fetch current state to ensure we have latest message for given element ID
            onElementIdChanged()
        }
    }

    @ThreadSafeProperty("Accessed during layout changes and size callbacks")
    internal var contentWidthInDp: Double? by threadSafe()

    @ThreadSafeProperty("Accessed during layout changes and size callbacks")
    internal var contentHeightInDp: Double? by threadSafe()

    private var stateSubscriptionJob: Job? = null
    private var isViewActive = false
    private var viewTransitionId = 0L

    init {
        viewDelegate.isVisible = false
    }

    private fun subscribeToStore() {
        stateSubscriptionJob = inAppMessagingManager.subscribeToState(
            areEquivalent = { oldState, newState ->
                val viewElementId = elementId ?: return@subscribeToState true

                val oldMessage = oldState.queuedInlineMessagesState.getMessage(viewElementId)
                val newMessage = newState.queuedInlineMessagesState.getMessage(viewElementId)
                val oldMessageMatchesRoute = oldMessage?.message?.matchesRoute(oldState.currentRoute)
                val newMessageMatchesRoute = newMessage?.message?.matchesRoute(newState.currentRoute)
                return@subscribeToState oldMessage == newMessage &&
                    oldMessageMatchesRoute == newMessageMatchesRoute
            }
        ) { state ->
            viewDelegate.post { refreshViewState(state = state) }
        }
    }

    private fun unsubscribeFromStore() {
        stateSubscriptionJob?.cancel()
        stateSubscriptionJob = null
    }

    private fun onElementIdChanged() {
        // Since the elementId has been changed, we need to fetch
        // current state to ensure correct message is displayed
        val state = inAppMessagingManager.getCurrentState()
        viewDelegate.post { refreshViewState(state = state) }
    }

    @UiThread
    internal fun onViewOwnerCreated() {
        isViewActive = true
        unsubscribeFromStore()
        subscribeToStore()
    }

    @UiThread
    internal fun onViewOwnerDestroyed() {
        val message = currentMessage
        val shouldDismissMessage = message != null && platformDelegate.shouldDestroyWithOwner()
        onViewDetached(shouldUpdateAttachmentState = shouldDismissMessage)

        if (message != null && shouldDismissMessage) {
            logViewEvent("View owner destroyed, dismissing inline message view for elementId=$elementId")
            inAppMessagingManager.dispatch(
                InAppMessagingAction.DismissMessage(
                    message = message,
                    shouldLog = false,
                    viaCloseAction = false
                )
            )
        }
    }

    /**
     * Releases resources owned by this view without dismissing the message from shared state.
     *
     * A view can be detached while its lifecycle owner remains active, for example when a
     * Compose lazy item leaves composition. Keeping the message embedded allows a recreated view
     * to render it again without recording another display event.
     *
     * @param shouldUpdateAttachmentState False for configuration changes, where the replacement
     * view should inherit the displayed message even if it is no longer in the server queue.
     */
    @UiThread
    internal fun onViewDetached(shouldUpdateAttachmentState: Boolean = true) {
        val message = currentMessage
        isViewActive = false
        unsubscribeFromStore()
        releaseMessageView()
        message?.takeIf { shouldUpdateAttachmentState && it.queueId != null }?.let {
            inAppMessagingManager.dispatch(
                InAppMessagingAction.SetInlineMessageViewAttached(
                    message = it,
                    isAttached = false
                )
            )
        }
    }

    @UiThread
    private fun refreshViewState(state: InAppMessagingState) {
        if (!isViewActive) return

        val viewElementId = elementId ?: return
        val inlineMessageState = state.queuedInlineMessagesState.getMessage(viewElementId)
        if (inlineMessageState == null) {
            currentMessage?.let { message -> dismissMessage(message) {} }
            return
        }
        if (!inlineMessageState.message.matchesRoute(state.currentRoute)) {
            currentMessage?.let { message ->
                dismissMessage(message) {
                    refreshViewState(inAppMessagingManager.getCurrentState())
                }
            }
            return
        }

        when (inlineMessageState) {
            is InlineMessageState.ReadyToEmbed -> {
                if (currentMessage == inlineMessageState.message) {
                    logViewEvent("Current message is the same as new message, no need to embed again: ${inlineMessageState.message.messageId}")
                } else {
                    embedMessage(message = inlineMessageState.message)
                }
            }

            is InlineMessageState.Dismissed -> dismissMessage(message = inlineMessageState.message) {}
            is InlineMessageState.Embedded -> {
                // The message is already embedded, but we are not displaying it. This can happen
                // when the message was already displayed in UI but the view was recreated.
                // In this case, we need to show the message again.
                if (currentMessage == null) {
                    logViewEvent("View recreated, embedding inline message again: ${inlineMessageState.message.messageId}")
                    embedMessage(
                        message = inlineMessageState.message,
                        shouldDispatchDisplayEvent = false
                    )
                    inAppMessagingManager.dispatch(
                        InAppMessagingAction.SetInlineMessageViewAttached(
                            message = inlineMessageState.message,
                            isAttached = true
                        )
                    )
                }
            }
        }
    }

    @UiThread
    private fun embedMessage(
        message: Message,
        shouldDispatchDisplayEvent: Boolean = true
    ) {
        logViewEvent("Loading inline message: ${message.messageId}")

        val oldMessage = currentMessage
        // If no message is currently displayed, show the new one
        if (oldMessage == null) {
            this.shouldDispatchDisplayEvent = shouldDispatchDisplayEvent
            displayMessage(message)
            return
        }

        // Else, dismiss the old one before showing the new one
        dismissMessage(oldMessage) {
            this.shouldDispatchDisplayEvent = shouldDispatchDisplayEvent
            displayMessage(message)
        }
    }

    @UiThread
    private fun dismissMessage(message: Message, onComplete: (() -> Unit)) {
        logViewEvent("Dismissing inline message: ${message.messageId}")
        val transitionId = ++viewTransitionId
        stopEngineWebViewLoading()
        viewDelegate.post {
            platformDelegate.animateViewSize(
                heightInDp = 0.0,
                onEnd = {
                    if (transitionId != viewTransitionId) return@animateViewSize

                    releaseMessageView(shouldStopLoading = false)
                    if (message.queueId != null) {
                        inAppMessagingManager.dispatch(
                            InAppMessagingAction.SetInlineMessageViewAttached(
                                message = message,
                                isAttached = false
                            )
                        )
                    }
                    onComplete()
                }
            )
        }
    }

    @UiThread
    private fun releaseMessageView(shouldStopLoading: Boolean = true) {
        if (currentMessage == null && engineWebViewDelegate == null) return

        viewTransitionId++
        if (shouldStopLoading) {
            stopEngineWebViewLoading()
        }
        currentMessage = null
        viewDelegate.isVisible = false
        contentWidthInDp = null
        contentHeightInDp = null
        shouldDispatchDisplayEvent = true
        detachAndCleanupEngineWebView()
        viewCallback?.onNoMessageToDisplay()
    }

    @UiThread
    private fun displayMessage(message: Message) {
        viewTransitionId++
        elapsedTimer.start("Displaying inline message: ${message.messageId}")
        viewCallback?.onLoadingStarted()
        attachEngineWebView()
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

    override fun onWebViewSizeUpdated(widthInDp: Double, heightInDp: Double) {
        super.onWebViewSizeUpdated(widthInDp, heightInDp)
        viewDelegate.post {
            platformDelegate.animateViewSize(
                widthInDp = widthInDp,
                heightInDp = heightInDp,
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
