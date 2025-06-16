package io.customer.messaginginapp.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ProgressBar
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.graphics.drawable.DrawableCompat
import io.customer.messaginginapp.R
import io.customer.messaginginapp.ui.bridge.AndroidInAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.InlineInAppMessageViewCallback
import io.customer.messaginginapp.ui.core.BaseInlineInAppMessageView
import io.customer.messaginginapp.ui.extensions.resolveThemeColor

/**
 * InlineInAppMessageView is a custom view that displays an inline in-app message for given
 * elementId. The view shows a progress indicator while the message is loading and hides it once the
 * message is displayed. If there is no message to display, the view will hide itself and display
 * automatically when a new message is available.
 *
 * Example usage in XML:
 *
 * <io.customer.messaginginapp.ui.InlineInAppMessageView
 *     android:id="@+id/inline_in_app_message"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:elementId="banner-message"/>
 */
class InlineInAppMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : BaseInlineInAppMessageView(context, attrs, defStyleAttr, defStyleRes),
    InlineInAppMessageViewCallback {
    override val platformDelegate = AndroidInAppPlatformDelegate(view = this)
    private val progressIndicator: ProgressBar = ProgressBar(context)

    /**
     * Sets the tint color for the progress indicator.
     * @param color The color to set for the progress indicator.
     */
    fun setProgressTint(@ColorInt color: Int) {
        runCatching {
            progressIndicator.indeterminateDrawable?.mutate()?.let { drawable ->
                DrawableCompat.setTint(
                    DrawableCompat.wrap(drawable),
                    color
                )
            }
        }
    }

    init {
        var progressColor = resolveThemeColor(android.R.attr.colorControlActivated, Color.GRAY)
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.InlineInAppMessageView)
        try {
            controller.elementId =
                typedArray.getString(R.styleable.InlineInAppMessageView_elementId)
            if (typedArray.hasValue(R.styleable.InlineInAppMessageView_progressTint)) {
                progressColor = typedArray.getColor(
                    R.styleable.InlineInAppMessageView_progressTint,
                    progressColor
                )
            }
        } finally {
            typedArray.recycle()
        }
        setupProgressIndicator(progressColor)
        configureView()
    }

    private fun setupProgressIndicator(@ColorInt progressColor: Int) {
        progressIndicator.isIndeterminate = true
        progressIndicator.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        runCatching {
            progressIndicator.indeterminateDrawable?.mutate()?.let { drawable ->
                DrawableCompat.setTint(
                    DrawableCompat.wrap(drawable),
                    progressColor
                )
            }
        }
        progressIndicator.visibility = GONE
        this.addView(progressIndicator)
    }

    override fun onLoadingStarted() {
        // Set a minimum height to ensure visibility
        // Keep the animation duration shorter so it can be completed before we
        // start receiving size update callbacks from WebView to avoid flickering
        platformDelegate.animateViewSize(
            heightInDp = 48.0,
            duration = 100,
            onStart = {
                progressIndicator.visibility = VISIBLE
                progressIndicator.bringToFront()
            }
        )
    }

    override fun onLoadingFinished() {
        progressIndicator.visibility = GONE
    }

    override fun onNoMessageToDisplay() {
        progressIndicator.visibility = GONE
    }
}
