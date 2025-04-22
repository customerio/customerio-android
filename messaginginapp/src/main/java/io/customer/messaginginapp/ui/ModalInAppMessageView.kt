package io.customer.messaginginapp.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import io.customer.messaginginapp.state.InAppMessagingAction

/**
 * Final implementation of the [InlineInAppMessageBaseView] for displaying modal in-app messages.
 * The view should be directly added to activity layout for displaying modal in-app messages.
 */
internal class ModalInAppMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : InAppMessageBaseView(
    context,
    attrs,
    defStyleAttr,
    defStyleRes
) {
    internal var viewListener: ModalInAppMessageViewListener? = null
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
        viewListener = null
    }

    override fun sizeChanged(width: Double, height: Double) {
        logViewEvent("Modal in-app message size changed: $width x $height")
        viewListener?.onViewSizeChanged(dpToPixels(width), dpToPixels(height))
    }
}
