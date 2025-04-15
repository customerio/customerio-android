package io.customer.messaginginapp.ui

import android.util.DisplayMetrics

internal fun InAppMessageBaseView.getSizeBasedOnDPI(size: Int): Int {
    return size * context.resources.displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT
}
