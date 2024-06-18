/* ktlint-disable filename */ // until this extension file contains 2+ functions in it, we will disable this ktlint rule.
package io.customer.messagingpush.extensions

import android.graphics.Color
import androidx.annotation.ColorInt
import io.customer.sdk.core.di.SDKComponent

@ColorInt
internal fun String.toColorOrNull(): Int? = try {
    Color.parseColor(this)
} catch (ex: IllegalArgumentException) {
    SDKComponent.logger.error("Invalid color string $this, ${ex.message}")
    null
}
