package io.customer.messagingpush.extensions

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.android.CustomerIO

@DrawableRes
internal fun Context.getDrawableByName(name: String?): Int? = if (name.isNullOrBlank()) {
    null
} else {
    resources?.getIdentifier(name, "drawable", packageName)?.takeUnless { id ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            id == Resources.ID_NULL
        } else {
            id == 0
        }
    }
}

@ColorInt
internal fun Context.getColorOrNull(@ColorRes id: Int): Int? = try {
    ContextCompat.getColor(this, id)
} catch (ex: Resources.NotFoundException) {
    CustomerIOShared.instance().diStaticGraph.logger.error("Invalid resource $id, ${ex.message}")
    null
}

/**
 * Gets initialized instance of SDK. If the SDK is not initialized, we
 * try to initialize the SDK and messaging push module earlier than requested
 * by wrapper SDKs using stored values if available.
 *
 * By initializing the module early, we can register activity lifecycle callback
 * required by messaging push module whenever we initialize the SDK using context.
 * This helps us tracking metrics in wrapper SDKs for notifications received when
 * app was in terminated state.
 */
internal fun Context.getSDKInstanceOrNull(): CustomerIO? {
    return CustomerIO.instanceOrNull(this, listOf(ModuleMessagingPushFCM()))
}
