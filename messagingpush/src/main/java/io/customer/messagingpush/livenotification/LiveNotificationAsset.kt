package io.customer.messagingpush.livenotification

import android.net.Uri
import androidx.annotation.DrawableRes

/**
 * A strongly-typed image source for a live notification, passed directly to
 * [LiveNotificationBranding.logo]. The SDK loads it when the notification renders.
 */
sealed interface LiveNotificationAsset {
    /** A bundled drawable resource. */
    data class Drawable(@DrawableRes val resId: Int) : LiveNotificationAsset

    /** A `file://`, `content://`, or `android.resource://` image. */
    data class Resource(val uri: Uri) : LiveNotificationAsset

    /** Raw encoded image bytes (PNG/JPEG/…). */
    class Bytes(val data: ByteArray) : LiveNotificationAsset {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    /** A remote http(s) image, downloaded and disk-cached at render time. */
    data class RemoteUrl(val url: String) : LiveNotificationAsset
}
