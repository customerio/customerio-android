package io.customer.messaginginapp.ui.controller

import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewCallback

internal class ModalInAppMessageViewController(
    viewDelegate: InAppHostViewDelegate,
    platformDelegate: InAppPlatformDelegate
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
        super.onRouteLoaded(message, route)
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
}
