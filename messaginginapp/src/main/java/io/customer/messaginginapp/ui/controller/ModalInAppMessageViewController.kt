package io.customer.messaginginapp.ui.controller

import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewCallback

internal class ModalInAppMessageViewController(
    viewDelegate: InAppHostViewDelegate,
    platformDelegate: InAppPlatformDelegate,
    /** Injectable so tests can drive the guard's clock instead of sleeping. */
    private val sizePolicy: ModalSizePolicy = ModalSizePolicy()
) : InAppMessageViewController<ModalInAppMessageViewCallback>(
    type = "Modal",
    viewDelegate = viewDelegate,
    platformDelegate = platformDelegate
) {
    init {
        attachEngineWebView()
    }

    override fun detachEngineWebView(): Boolean {
        val result = super.detachEngineWebView()
        if (result) {
            viewCallback = null
        }
        return result
    }

    override fun onRouteLoaded(message: Message, route: String) {
        engineWebViewDelegate?.setAlpha(1.0F)
        // Start judging reported heights from the point the message is handed over for display.
        // Heights measured before this belong to a WebView that may not be laid out yet, and the
        // policy additionally requires the collapsed reports to span real time so the ones that
        // arrive while the modal is still being laid out cannot fail a healthy message.
        sizePolicy.arm()
        super.onRouteLoaded(message, route)
    }

    override fun onWebViewSizeUpdated(widthInDp: Double, heightInDp: Double) {
        val message = currentMessage
        if (message == null) {
            // Without a message there is nothing to fail, and consuming a verdict we cannot act on
            // would disable the guard for the rest of this message's life.
            super.onWebViewSizeUpdated(widthInDp, heightInDp)
            return
        }

        when (val verdict = sizePolicy.onHeightReported(heightInDp)) {
            is ModalSizeVerdict.Degenerate -> failCollapsedMessage(message)

            is ModalSizeVerdict.ViewportDependent -> {
                logViewportDependentHeight(message, verdict.deltaInDp)
                sizePolicy.onViewportDependentHandled()
                super.onWebViewSizeUpdated(widthInDp, heightInDp)
            }

            is ModalSizeVerdict.Apply -> super.onWebViewSizeUpdated(widthInDp, heightInDp)
        }
    }

    /**
     * The message is displayed but collapsed, so it covers the screen and swallows touches without
     * ever showing anything. Failing it dismisses the overlay and tells the host app why.
     *
     * [InAppMessagingAction.EngineAction.MessageLoadingFailed.suppressRetry] is required here: a
     * message whose CSS cannot resolve will collapse again every time, and persistent messages are
     * never marked as shown when displayed, so without it the message would be fetched and shown
     * again indefinitely.
     */
    private fun failCollapsedMessage(message: Message) {
        logViewError(
            "Message ${message.messageId} reported a collapsed height " +
                "(<= ${sizePolicy.degenerateMaxDp.toInt()}dp) for ${sizePolicy.sampleCount} " +
                "consecutive updates over ${sizePolicy.degenerateMinElapsedMs}ms, so it can never " +
                "become visible while still blocking the screen. Dismissing it and not retrying it. " +
                VIEWPORT_HEIGHT_HINT
        )
        inAppMessagingManager.dispatch(
            InAppMessagingAction.EngineAction.MessageLoadingFailed(
                message = message,
                suppressRetry = true
            )
        )
        sizePolicy.onDegenerateHandled()
    }

    private fun logViewportDependentHeight(message: Message, deltaInDp: Double) {
        logViewError(
            "Message ${message.messageId} keeps growing by ${deltaInDp}dp per update, so its " +
                "height tracks the WebView height instead of its content. It will grow until the " +
                "layout can grow no further, and its content may not be positioned as designed. " +
                VIEWPORT_HEIGHT_HINT
        )
    }

    override fun bootstrapped() {
        super.bootstrapped()
        // Cleaning after engine web is bootstrapped and all assets downloaded.
        clearResourcesIfMessageIdEmpty()
    }

    private fun clearResourcesIfMessageIdEmpty() {
        val message = currentMessage ?: return
        if (message.messageId.isNotBlank()) return

        logViewEvent("Clearing resources for empty messageId")
        detachEngineWebView()
        currentMessage = null
    }

    private companion object {
        const val VIEWPORT_HEIGHT_HINT: String =
            "This usually means the message HTML derives its own height from the viewport " +
                "(height: 100vh or height: 100% on html/body). The SDK sizes the WebView to the " +
                "height the message reports, so a viewport based height can never resolve; give " +
                "the message a content driven height instead."
    }
}
