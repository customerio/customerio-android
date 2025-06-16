package io.customer.messaginginapp.ui.core

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegateImpl
import io.customer.messaginginapp.ui.bridge.InAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.InlineInAppMessageViewCallback
import io.customer.messaginginapp.ui.controller.InlineInAppMessageViewController

/**
 * Abstract base class for inline in-app message views.
 *
 * Provides core functionality for displaying inline messages including element ID management
 * and action listener support. Subclasses must implement [platformDelegate] to provide
 * platform-specific rendering and interaction handling.
 */
abstract class BaseInlineInAppMessageView
@InternalCustomerIOApi
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes), InlineInAppMessageViewCallback {
    protected abstract val platformDelegate: InAppPlatformDelegate
    internal val controller: InlineInAppMessageViewController by lazy {
        InlineInAppMessageViewController(
            viewDelegate = InAppHostViewDelegateImpl(view = this),
            platformDelegate = platformDelegate
        )
    }

    @InternalCustomerIOApi
    protected fun configureView() {
        controller.viewCallback = this
    }

    /**
     * Element ID for targeting this view with specific inline messages.
     */
    var elementId: String?
        get() = controller.elementId
        set(value) {
            controller.elementId = value
        }

    /**
     * Sets a listener to receive callbacks when actions are triggered in the inline message.
     * @param listener The listener that will handle action clicks.
     */
    fun setActionListener(listener: InlineMessageActionListener) {
        controller.actionListener = listener
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        controller.onDetachedFromWindow()
    }
}
