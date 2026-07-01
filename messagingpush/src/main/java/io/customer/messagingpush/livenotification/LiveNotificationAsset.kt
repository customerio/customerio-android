package io.customer.messagingpush.livenotification

import android.net.Uri
import androidx.annotation.DrawableRes

/**
 * An image asset registered by the host app and addressable by a string `key`
 * from live-notification templates (e.g. a team `logoKey`) and branding
 * ([LiveNotificationBranding.logoAssetKey]).
 *
 * Mirrors the behavior of the iOS asset library — assets are declared once at
 * configuration time and resolved by key when a notification renders — without
 * the iOS-specific on-disk machinery (App Group container, manifest, hashing):
 * Android live notifications render in-process, so a registered source is loaded
 * directly when needed.
 *
 * Register via [io.customer.messagingpush.MessagingPushModuleConfig.Builder.registerLiveNotificationAsset].
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
}
