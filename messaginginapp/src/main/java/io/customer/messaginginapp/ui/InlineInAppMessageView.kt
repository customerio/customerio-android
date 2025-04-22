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

class InlineInAppMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : InlineInAppMessageBaseView(context, attrs, defStyleAttr, defStyleRes),
    InlineInAppMessageViewListener {
    private val progressIndicator: ProgressBar = ProgressBar(context)

    init {
        var progressColor = resolveThemeColor(android.R.attr.colorControlActivated, Color.GRAY)
        context.obtainStyledAttributes(attrs, R.styleable.InlineInAppMessageView).use { ta ->
            elementId = ta.getString(R.styleable.InlineInAppMessageView_elementId)
            if (ta.hasValue(R.styleable.InlineInAppMessageView_progressTint)) {
                progressColor =
                    ta.getColor(R.styleable.InlineInAppMessageView_progressTint, progressColor)
            }
        }
        setupProgressIndicator(progressColor)
        viewListener = this
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
        super.onLoadingStarted()
        progressIndicator.visibility = VISIBLE
        progressIndicator.bringToFront()
    }

    override fun onLoadingFinished() {
        super.onLoadingFinished()
        progressIndicator.visibility = GONE
    }

    override fun onNoMessageToDisplay() {
        super.onNoMessageToDisplay()
        progressIndicator.visibility = GONE
    }
}
