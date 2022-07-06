package io.customer.messagingpush.notification

import android.net.Uri
import androidx.annotation.DrawableRes

data class PushNotificationAttributes(
    @DrawableRes val iconResId: Int? = null,
    val titleText: String? = null,
    val bodyText: String? = null,
    val autoCancel: Boolean? = null,
    val soundUri: Uri? = null,
    val tickerText: String? = null
)
