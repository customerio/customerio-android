package io.customer.messaginginapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.UiThread
import androidx.core.view.updateLayoutParams

/**
 * Converts the given size from dp to pixels based on the device's screen density.
 */
internal fun InAppMessageBaseView.dpToPixels(size: Double): Int {
    return size.toInt() * context.resources.displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT
}

/**
 * Updates the layout parameters of the view with the specified width and height with animation
 * and optional callbacks for start and end events.
 */
@UiThread
internal fun View.animateViewSize(
    widthInPx: Int? = null,
    heightInPx: Int? = null,
    duration: Long = resources.getInteger(android.R.integer.config_longAnimTime).toLong(),
    onStart: (() -> Unit)? = null,
    onEnd: (() -> Unit)? = null
) {
    if (widthInPx == null && heightInPx == null) {
        onStart?.invoke()
        onEnd?.invoke()
        return
    }

    val animators = mutableListOf<Animator>()

    widthInPx?.let { targetWidth ->
        val animator = ValueAnimator.ofInt(width, targetWidth).apply {
            this.duration = duration
            addUpdateListener { update ->
                updateLayoutParams { width = update.animatedValue as Int }
            }
        }
        animators.add(animator)
    }

    heightInPx?.let { targetHeight ->
        val animator = ValueAnimator.ofInt(height, targetHeight).apply {
            this.duration = duration
            addUpdateListener { update ->
                updateLayoutParams { height = update.animatedValue as Int }
            }
        }
        animators.add(animator)
    }

    AnimatorSet().apply {
        playTogether(animators)
        this.duration = duration
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                super.onAnimationStart(animation)
                onStart?.invoke()
            }

            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                updateLayoutParams {
                    widthInPx?.let { width = it }
                    heightInPx?.let { height = it }
                }
                onEnd?.invoke()
            }
        })
        start()
    }
}

/**
 * Resolves the theme color for the given attribute resource ID. If the attribute is not found,
 * it returns the fallback color.
 */
internal fun InAppMessageBaseView.resolveThemeColor(
    @AttrRes attrResId: Int,
    @ColorInt fallbackColor: Int
): Int = try {
    val typedValue = TypedValue()
    if (context.theme.resolveAttribute(attrResId, typedValue, true)) {
        typedValue.data
    } else {
        fallbackColor
    }
} catch (_: Throwable) {
    fallbackColor
}
