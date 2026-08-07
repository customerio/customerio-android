package io.customer.messaginginapp.ui.controller

import androidx.annotation.VisibleForTesting
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewCallback
import io.customer.sdk.core.di.SDKComponent

internal class ModalInAppMessageViewController(
    viewDelegate: InAppHostViewDelegate,
    platformDelegate: InAppPlatformDelegate
) : InAppMessageViewController<ModalInAppMessageViewCallback>(
    type = "Modal",
    viewDelegate = viewDelegate,
    platformDelegate = platformDelegate
) {
    private val logger = SDKComponent.logger

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val sizePolicy: ModalSizePolicy = ModalSizePolicy()

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
        // Only start judging reported heights once the message is on screen. While it loads the
        // WebView is still detached and legitimately measures zero.
        sizePolicy.arm()
        super.onRouteLoaded(message, route)
    }

    override fun onWebViewSizeUpdated(widthInDp: Double, heightInDp: Double) {
        when (val verdict = sizePolicy.onHeightReported(heightInDp)) {
            is ModalSizeVerdict.Degenerate -> failCollapsedMessage()

            is ModalSizeVerdict.ViewportDependent -> {
                logViewportDependentHeight(verdict.deltaInDp)
                super.onWebViewSizeUpdated(widthInDp, heightInDp)
            }

            is ModalSizeVerdict.Apply -> super.onWebViewSizeUpdated(widthInDp, heightInDp)
        }
    }

    /**
     * The message is displayed but collapsed, so it covers the screen and swallows touches without
     * ever showing anything. Failing it dismisses the overlay and tells the host app why.
     */
    private fun failCollapsedMessage() {
        val message = currentMessage ?: return
        logger.error(
            "In-app message ${message.messageId} reported a collapsed height " +
                "(<= ${ModalSizePolicy.DEGENERATE_MAX_DP.toInt()}dp) for " +
                "${ModalSizePolicy.SAMPLE_COUNT} consecutive updates, so it can never become " +
                "visible while still blocking the screen. Dismissing it. $VIEWPORT_HEIGHT_HINT"
        )
        inAppMessagingManager.dispatch(
            InAppMessagingAction.EngineAction.MessageLoadingFailed(message)
        )
    }

    private fun logViewportDependentHeight(deltaInDp: Double) {
        logger.error(
            "In-app message ${currentMessage?.messageId} keeps growing by ${deltaInDp}dp per " +
                "update, so its height tracks the WebView height instead of its content. It will " +
                "be clamped to the screen and its content may not be positioned as designed. " +
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
