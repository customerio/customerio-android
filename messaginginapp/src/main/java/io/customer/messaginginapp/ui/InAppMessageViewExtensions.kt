package io.customer.messaginginapp.ui

import android.util.DisplayMetrics

/**
 * Converts the given size from dp to pixels based on the device's screen density.
 */
internal fun InAppMessageBaseView.dpToPixels(size: Double): Int {
    return size.toInt() * context.resources.displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT
}
