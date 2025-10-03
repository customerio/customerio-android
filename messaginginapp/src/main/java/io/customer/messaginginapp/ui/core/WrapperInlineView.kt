package io.customer.messaginginapp.ui.core

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.ui.bridge.WrapperPlatformDelegate
import io.customer.messaginginapp.ui.bridge.WrapperStateEvent

/**
 * Cross-platform view implementation that eliminates duplication between Flutter and React Native.
 * Contains all the common lifecycle management and initialization logic that was duplicated.
 *
 * This abstract class extends BaseInlineInAppMessageView and provides a unified foundation
 * for cross-platform inline in-app message views. It handles common lifecycle events,
 * initialization patterns, and state management that were previously duplicated across
 * different platform implementations.
 *
 * @param T The type of WrapperPlatformDelegate that this view will use
 */
@InternalCustomerIOApi
abstract class WrapperInlineView<T : WrapperPlatformDelegate> @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : BaseInlineInAppMessageView<T>(context, attrs, defStyleAttr, defStyleRes) {

    /**
     * Initialize the view after platformDelegate is set.
     * Called by subclasses after they set the platformDelegate.
     *
     * This method sets up the standard layout parameters and calls the base configuration.
     * It should be called once the platform delegate has been properly initialized
     * to ensure all components are ready for use.
     */
    protected fun initializeView() {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        configureView()
    }

    /**
     * Loading state callbacks - no more duplication!
     * Routes through the platform delegate for consistent event handling.
     *
     * This method is called when the in-app message starts loading.
     * It uses the platform delegate to ensure consistent
     * event handling across all platform implementations.
     */
    override fun onLoadingStarted() {
        platformDelegate.sendLoadingStateEvent(WrapperStateEvent.LoadingStarted)
    }

    /**
     * Loading state callbacks - no more duplication!
     * Routes through the platform delegate for consistent event handling.
     *
     * This method is called when the in-app message finishes loading successfully.
     * It uses the platform delegate to ensure consistent
     * event handling across all platform implementations.
     */
    override fun onLoadingFinished() {
        platformDelegate.sendLoadingStateEvent(WrapperStateEvent.LoadingFinished)
    }

    /**
     * Loading state callbacks - no more duplication!
     * Routes through the platform delegate for consistent event handling.
     *
     * This method is called when there is no message available to display.
     * It uses the platform delegate to ensure consistent
     * event handling across all platform implementations.
     */
    override fun onNoMessageToDisplay() {
        platformDelegate.sendLoadingStateEvent(WrapperStateEvent.NoMessageToDisplay)
    }
}
