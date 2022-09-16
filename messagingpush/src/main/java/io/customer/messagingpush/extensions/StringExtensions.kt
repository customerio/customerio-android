/* ktlint-disable filename */ // until this extension file contains 2+ functions in it, we will disable this ktlint rule.
package io.customer.messagingpush.extensions

import android.graphics.Color
import androidx.annotation.DrawableRes
import io.customer.sdk.CustomerIO

@DrawableRes
internal fun String.toColorOrNull(): Int? = try {
    Color.parseColor(this)
} catch (ex: IllegalArgumentException) {
    CustomerIO.instance().diGraph.logger.error("Invalid color string $this, ${ex.message}")
    null
}
