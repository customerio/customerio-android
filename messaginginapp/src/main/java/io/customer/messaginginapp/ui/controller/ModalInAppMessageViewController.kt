package io.customer.messaginginapp.ui.controller

import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegate
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewCallback

@InternalCustomerIOApi
internal class ModalInAppMessageViewController(
    viewDelegate: InAppHostViewDelegate,
    platformDelegate: InAppPlatformDelegate
) : InAppMessageViewController<ModalInAppMessageViewCallback>(
    type = "Modal",
    viewDelegate = viewDelegate,
    platformDelegate = platformDelegate
) {
    private var isMessageDisplayed: Boolean = true

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

    override fun routeLoaded(route: String) {
        super.routeLoaded(route)
        if (!isMessageDisplayed) return

        isMessageDisplayed = false
        engineWebViewDelegate?.setAlpha(1.0F)
        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(message))
        }
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
