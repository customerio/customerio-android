package io.customer.messaginginapp.ui.core

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
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
abstract class BaseInlineInAppMessageView<P : InAppPlatformDelegate>
@InternalCustomerIOApi
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes), InlineInAppMessageViewCallback {
    protected abstract val platformDelegate: P
    internal val controller: InlineInAppMessageViewController by lazy {
        InlineInAppMessageViewController(
            viewDelegate = InAppHostViewDelegateImpl(view = this),
            platformDelegate = platformDelegate
        )
    }

    private val lifecycleObserver: DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) {
            super.onCreate(owner)
            // Notify controller when owner is created so it can subscribe to in-app messaging state
            onViewOwnerCreated()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            super.onDestroy(owner)
            // Remove observer to prevent further callbacks after destruction
            viewLifecycleOwner?.removeObserver(this)
            // Clean up controller resources to prevent memory leaks
            onViewOwnerDestroyed()
        }
    }

    private val viewLifecycleOwner: Lifecycle?
        get() = findViewTreeLifecycleOwner()?.lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Register lifecycle observer to clean up resources when the view is fully destroyed
        viewLifecycleOwner?.addObserver(lifecycleObserver)
    }

    @InternalCustomerIOApi
    protected fun configureView() {
        controller.viewCallback = this
    }

    @InternalCustomerIOApi
    protected fun onViewOwnerCreated() {
        controller.onViewOwnerCreated()
    }

    @InternalCustomerIOApi
    protected fun onViewOwnerDestroyed() {
        controller.onViewOwnerDestroyed()
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
}
