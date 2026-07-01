package io.customer.messagingpush.livenotification

import androidx.annotation.ColorInt

/**
 * App-level branding applied to live notifications.
 *
 * Registered once via [io.customer.messagingpush.MessagingPushModuleConfig.Builder.setLiveNotificationBranding]
 * and shared across every templated live notification this app posts. Templates
 * may override individual fields (e.g. accent color flips green/red for auction
 * winning/outbid state); when they do not, these values are used.
 *
 * @property companyName Reserved for future templates that need to render a
 *   company label. Not consumed by any v1 template mapping.
 * @property accentColor Default accent color applied via [android.app.Notification.Builder.setColor].
 * @property logoDrawableName Optional drawable resource name (looked up via
 *   `Context.getDrawableByName`) that overrides the **small icon** for live
 *   notifications only. The standard push channel still uses the small icon
 *   declared in FCM metadata. Hyphens are normalized to underscores before
 *   lookup so values like `cio-logo` resolve to `R.drawable.cio_logo`. The small
 *   icon is a status-bar glyph, so it must be a bundled drawable resource — a
 *   remote/registered image cannot fill this slot (use [logoAssetKey] for that).
 * @property logoAssetKey Optional image key resolved through the full asset
 *   pipeline (remote `http(s)` URL, host-registered asset, or bundled drawable
 *   name) and rendered as the color **large icon** when the active template does
 *   not provide one of its own.
 */
data class LiveNotificationBranding(
    val companyName: String,
    @ColorInt val accentColor: Int,
    val logoDrawableName: String? = null,
    val logoAssetKey: String? = null
)
