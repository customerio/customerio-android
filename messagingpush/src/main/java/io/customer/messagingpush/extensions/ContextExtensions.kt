package io.customer.messagingpush.extensions

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.customer.sdk.CustomerIOShared

@DrawableRes
internal fun Context.getDrawableByName(name: String?): Int? = if (name.isNullOrBlank()) null
else resources?.getIdentifier(name, "drawable", packageName)?.takeUnless { id ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) id == Resources.ID_NULL
    else id == 0
}

@ColorInt
internal fun Context.getColorOrNull(@ColorRes id: Int): Int? = try {
    ContextCompat.getColor(this, id)
} catch (ex: Resources.NotFoundException) {
    CustomerIOShared.instance().diStaticGraph.logger.error("Invalid resource $id, ${ex.message}")
    null
}
