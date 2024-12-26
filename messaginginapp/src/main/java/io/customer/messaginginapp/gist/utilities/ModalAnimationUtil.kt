package io.customer.messaginginapp.gist.utilities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.View
import androidx.annotation.ColorInt
import io.customer.sdk.core.di.SDKComponent

object ModalAnimationUtil {

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

    private fun createEnterAnimation(
        target: View,
        overlayEndColor: String,
        translationYStart: Float
    ): AnimatorSet {
        val translationYAnimator = ObjectAnimator.ofFloat(target, "translationY", translationYStart, 0f).apply {
            duration = TRANSLATION_ANIMATION_DURATION
        }
        val alphaAnimator = ObjectAnimator.ofFloat(target, "alpha", 0f, 1f).apply {
            duration = ALPHA_ANIMATION_DURATION
        }
        val translationAndAlphaSet = AnimatorSet().apply {
            playTogether(translationYAnimator, alphaAnimator)
        }

        val fallbackColor = Color.parseColor(FALLBACK_COLOR_STRING)
        val backgroundColorAnimator = ObjectAnimator.ofArgb(target, "backgroundColor", Color.TRANSPARENT, parseColorSafely(overlayEndColor, fallbackColor)).apply {
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

    @ColorInt
    private fun parseColorSafely(color: String, @ColorInt fallbackColor: Int): Int {
        return try {
            Color.parseColor(color)
        } catch (ignored: IllegalArgumentException) {
            logger.error(ignored.message ?: "Error parsing in-app overlay color")
            fallbackColor
        }
    }
}
