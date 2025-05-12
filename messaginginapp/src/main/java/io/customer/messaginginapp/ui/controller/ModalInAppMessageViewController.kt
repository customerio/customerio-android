package io.customer.messaginginapp.ui.controller

import android.view.ViewGroup
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewListener

internal class ModalInAppMessageViewController(
    viewDelegate: ViewGroup,
    platformDelegate: InAppPlatformDelegate
) : InAppMessageViewController<ModalInAppMessageViewListener>(
    type = "Modal",
    viewDelegate = viewDelegate,
    platformDelegate = platformDelegate
) {
    private var isMessageDisplayed: Boolean = true

    init {
        attachEngineWebView()
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
