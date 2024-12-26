package io.customer.messaginginapp.gist.utilities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.annotation.ColorInt
import io.customer.sdk.core.di.SDKComponent

internal object ModalAnimationUtil {

    const val FALLBACK_COLOR_STRING = "#33000000"

    private const val TRANSLATION_ANIMATION_DURATION = 150L
    private const val ALPHA_ANIMATION_DURATION = 150L
    private const val COLOR_ANIMATION_DURATION = 100L

    private val logger = SDKComponent.logger

    fun createAnimationSetInFromTop(target: View, overlayEndColor: String): AnimatorSet {
        return createEnterAnimation(target, overlayEndColor, -100f)
    }

    fun createAnimationSetInFromBottom(target: View, overlayEndColor: String): AnimatorSet {
        return createEnterAnimation(target, overlayEndColor, 100f)
    }

    fun createAnimationSetOutToTop(target: View): AnimatorSet {
        return createExitAnimation(target, -100f)
    }

    fun createAnimationSetOutToBottom(target: View): AnimatorSet {
        return createExitAnimation(target, 100f)
    }

    private fun createEnterAnimation(
        target: View,
        overlayEndColor: String,
        translationYStart: Float
    ): AnimatorSet {
        val translationYAnimator = ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, translationYStart, 0f).apply {
            duration = TRANSLATION_ANIMATION_DURATION
        }
        val alphaAnimator = ObjectAnimator.ofFloat(target, View.ALPHA, 0f, 1f).apply {
            duration = ALPHA_ANIMATION_DURATION
        }
        val translationAndAlphaSet = AnimatorSet().apply {
            playTogether(translationYAnimator, alphaAnimator)
        }
        target.alpha = 0f

        val fallbackColor = Color.parseColor(FALLBACK_COLOR_STRING)
        val backgroundColorAnimator = ObjectAnimator.ofArgb(
            target,
            "backgroundColor",
            Color.TRANSPARENT,
            parseColorSafely(overlayEndColor, fallbackColor)
        ).apply {
            duration = COLOR_ANIMATION_DURATION
            startDelay = 0
        }
        val colorSet = AnimatorSet().apply {
            play(backgroundColorAnimator)
        }

        return AnimatorSet().apply {
            playSequentially(translationAndAlphaSet, colorSet)
        }
    }

    private fun createExitAnimation(target: View, translationYEnd: Float): AnimatorSet {
        val fallbackColor = Color.parseColor(FALLBACK_COLOR_STRING)
        val backgroundColor = extractBackgroundColor(target)
        val backgroundColorAnimator = ObjectAnimator.ofArgb(target, "backgroundColor", parseColorSafely(backgroundColor, fallbackColor), Color.TRANSPARENT).apply {
            duration = COLOR_ANIMATION_DURATION
            startDelay = 0
        }
        val colorSet = AnimatorSet().apply {
            play(backgroundColorAnimator)
        }

        val translationYAnimator = ObjectAnimator.ofFloat(target, "translationY", 0f, translationYEnd).apply {
            duration = TRANSLATION_ANIMATION_DURATION
        }
        val alphaAnimator = ObjectAnimator.ofFloat(target, "alpha", 1f, 0f).apply {
            duration = ALPHA_ANIMATION_DURATION
        }
        val translationAndAlphaSet = AnimatorSet().apply {
            playTogether(translationYAnimator, alphaAnimator)
        }

        return AnimatorSet().apply {
            playSequentially(colorSet, translationAndAlphaSet)
        }
    }

    @ColorInt
    private fun parseColorSafely(color: String, @ColorInt fallbackColor: Int): Int {
        return try {
            Color.parseColor(color)
        } catch (ignored: IllegalArgumentException) {
            logger.error(ignored.message ?: "Error parsing in-app overlay color")
            fallbackColor
        }
    }

    private fun extractBackgroundColor(target: View): String {
        val backgroundDrawable = target.background
        if (backgroundDrawable is ColorDrawable) {
            return String.format("#%08X", backgroundDrawable.color)
        }

        return FALLBACK_COLOR_STRING
    }
}
