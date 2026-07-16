package io.customer.messagingpush.livenotification

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * App-level branding applied to live notifications, registered once via
 * [io.customer.messagingpush.MessagingPushModuleConfig.Builder.setLiveNotificationBranding]
 * and shared across every templated live notification this app posts.
 *
 * @property companyName Reserved for future templates; not consumed today.
 * @property accentColor Accent color applied via [android.app.Notification.Builder.setColor].
 * @property smallIcon Optional bundled drawable overriding the status-bar small
 *   icon for live notifications only (tinted with [accentColor]). Must be a
 *   bundled drawable resource, not a remote or byte-backed image.
 * @property logo Optional image rendered as the large icon when the active
 *   template does not provide one. Accepts any [LiveNotificationAsset].
 */
data class LiveNotificationBranding(
    val companyName: String,
    @ColorInt val accentColor: Int,
    @DrawableRes val smallIcon: Int? = null,
    val logo: LiveNotificationAsset? = null
)
