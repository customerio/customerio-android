package io.customer.messaginginapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.UiThread
import androidx.core.view.updateLayoutParams
import io.customer.sdk.core.di.SDKComponent

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
    val animators = mutableListOf<Animator>()

    widthInPx?.takeIf { it != width }?.let { targetWidth ->
        val animator = ValueAnimator.ofInt(width, targetWidth).apply {
            this.duration = duration
            addUpdateListener { update ->
                updateLayoutParams { width = update.animatedValue as Int }
            }
        }
        animators.add(animator)
    }

    heightInPx?.takeIf { it != height }?.let { targetHeight ->
        val animator = ValueAnimator.ofInt(height, targetHeight).apply {
            this.duration = duration
            addUpdateListener { update ->
                updateLayoutParams { height = update.animatedValue as Int }
            }
        }
        animators.add(animator)
    }

    // If all size changes were filtered out, just call callbacks
    if (animators.isEmpty()) {
        onStart?.invoke()
        onEnd?.invoke()
        return
    }

    AnimatorSet().apply {
        this.duration = duration
        playTogether(animators)
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

/**
 * Determines whether the view should be destroyed when detached from its parent.
 * The view should be destroyed if the activity is finishing or not changing configurations.
 */
internal fun View.shouldDestroyOnDetach(): Boolean {
    val activity = context?.findActivity()
    return activity?.let {
        it.isFinishing || !it.isChangingConfigurations
    } != false
}

/**
 * Safely finds the Activity from a Context by unwrapping ContextWrappers.
 * Limits depth to prevent infinite loops in case of badly implemented ContextWrapper
 * chains.
 * Default max depth is only 5 because we don't expect more than 3 wrappers in a normal case.
 */
internal fun Context.findActivity(maxDepth: Int = 5): Activity? {
    var currentContext = this
    var depth = maxDepth

    do {
        when (currentContext) {
            is Activity -> return currentContext
            is ContextWrapper -> currentContext = currentContext.baseContext
            else -> return null
        }
        depth--
    } while (depth > 0)

    SDKComponent.logger.debug(
        "Max depth ($maxDepth) exceeded while searching for Activity from Context $this"
    )
    return null
}
