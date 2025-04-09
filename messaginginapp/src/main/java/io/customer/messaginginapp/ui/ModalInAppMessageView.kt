package io.customer.messaginginapp.ui

import android.content.Context
import android.util.AttributeSet
import io.customer.messaginginapp.state.InAppMessagingAction

/**
 * Final implementation of the InAppMessageHostView for displaying modal in-app messages.
 * The view should be directly added to activity layout for displaying modal in-app messages.
 */
internal class ModalInAppMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : InAppMessageBaseView(context, attrs, defStyleAttr) {
    private var isMessageDisplayed: Boolean = true

    init {
        attachEngineWebView()
    }

    override fun routeLoaded(route: String) {
        super.routeLoaded(route)
        if (!isMessageDisplayed) return

        isMessageDisplayed = false
        engineWebView?.alpha = 1.0f
        currentMessage?.let { message ->
            inAppMessagingManager.dispatch(InAppMessagingAction.DisplayMessage(message))
        }
    }
}
