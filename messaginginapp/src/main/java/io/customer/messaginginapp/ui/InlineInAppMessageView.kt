package io.customer.messaginginapp.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.graphics.drawable.DrawableCompat
import io.customer.messaginginapp.R
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.messaginginapp.ui.bridge.AndroidInAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.InlineInAppMessageViewListener
import io.customer.messaginginapp.ui.controller.InlineInAppMessageViewController
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
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes),
    InlineInAppMessageViewListener {
    private val controller = InlineInAppMessageViewController(
        viewDelegate = this,
        platformDelegate = AndroidInAppPlatformDelegate(view = this)
    )
    private val progressIndicator: ProgressBar = ProgressBar(context)

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
        controller.viewCallback = this
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        controller.onDetachedFromWindow()
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
        progressIndicator.visibility = VISIBLE
        progressIndicator.bringToFront()
    }

    override fun onLoadingFinished() {
        progressIndicator.visibility = GONE
    }

    override fun onNoMessageToDisplay() {
        progressIndicator.visibility = GONE
    }
}
