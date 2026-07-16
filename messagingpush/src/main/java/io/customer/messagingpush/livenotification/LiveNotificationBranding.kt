package io.customer.messagingpush.livenotification

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * App-level branding applied to live notifications.
 *
 * Registered once via [io.customer.messagingpush.MessagingPushModuleConfig.Builder.setLiveNotificationBranding]
 * and shared across every templated live notification this app posts. Built-in
 * template styling is branding-only: the accent color and logo come from here
 * (with the FCM default tint as the accent fallback), not from per-push fields.
 *
 * @property companyName Reserved for future templates that need to render a
 *   company label. Not consumed by any v1 template mapping.
 * @property accentColor Default accent color applied via [android.app.Notification.Builder.setColor].
 * @property smallIcon Optional bundled drawable that overrides the **small icon**
 *   (the status-bar glyph) for live notifications only. The system tints it with
 *   [accentColor]. The standard push channel still uses the small icon declared
 *   in FCM metadata. The small icon must be a bundled drawable resource — remote
 *   or byte-backed images cannot fill this slot (use [logo] for those).
 * @property logo Optional image rendered as the color **large icon** when the
 *   active template does not provide one of its own. Pass any
 *   [LiveNotificationAsset] (bundled drawable, `content://`/`file://` URI, raw
 *   bytes, or a remote URL).
 */
data class LiveNotificationBranding(
    val companyName: String,
    @ColorInt val accentColor: Int,
    @DrawableRes val smallIcon: Int? = null,
    val logo: LiveNotificationAsset? = null
)
