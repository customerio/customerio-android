package io.customer.messaginginapp.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.annotation.UiThread
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.ui.bridge.AndroidInAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegateImpl
import io.customer.messaginginapp.ui.bridge.ModalInAppMessageViewCallback
import io.customer.messaginginapp.ui.controller.ModalInAppMessageViewController

/**
 * Final view for displaying modal in-app messages.
 * It utilizes [ModalInAppMessageViewController] to manage the lifecycle of the modal in-app message.
 * The view should be directly added to activity layout for displaying modal in-app messages.
 */
internal class ModalInAppMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
    private val controller = ModalInAppMessageViewController(
        viewDelegate = InAppHostViewDelegateImpl(view = this),
        platformDelegate = AndroidInAppPlatformDelegate(view = this)
    )

    internal fun setViewCallback(viewCallback: ModalInAppMessageViewCallback) {
        controller.viewCallback = viewCallback
    }

    /**
     * @param shouldDispatchDisplayEvent false when the message was already reported as displayed and is
     * only being re-created, for example in another host window, so the view is not logged twice.
     */
    @UiThread
    internal fun setup(message: Message, shouldDispatchDisplayEvent: Boolean = true) {
        controller.loadMessage(message = message, shouldDispatchDisplayEvent = shouldDispatchDisplayEvent)
    }

    @UiThread
    internal fun stopLoading() {
        controller.stopLoading()
    }
}
