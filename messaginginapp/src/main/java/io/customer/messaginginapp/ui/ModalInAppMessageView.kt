package io.customer.messaginginapp.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.annotation.UiThread
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.ui.bridge.AndroidInAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.ModalInAppHostViewDelegate
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
        viewDelegate = createModalViewDelegate(),
        platformDelegate = AndroidInAppPlatformDelegate(view = this)
    )

    private fun createModalViewDelegate(): ModalInAppHostViewDelegate {
        // Find the activity context to use for lifecycle
        val activity = context as? android.app.Activity
            ?: throw IllegalStateException("ModalInAppMessageView must be hosted in an Activity")

        return ModalInAppHostViewDelegate(activity, this)
    }

    internal fun setViewCallback(viewCallback: ModalInAppMessageViewCallback) {
        controller.viewCallback = viewCallback
    }

    @UiThread
    internal fun setup(message: Message) {
        controller.loadMessage(message = message)
    }

    @UiThread
    internal fun stopLoading() {
        controller.stopLoading()
    }
}
