package io.customer.messagingpush.livenotification.template

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * Renders a single live-notification template. The handler dispatches to a
 * concrete subtype via [TemplateRegistry.find] using the `activity_type` key.
 */
internal sealed interface LiveNotificationTemplate {
    val name: String

    /**
     * Renders [data] into a [TemplateRenderResult], or `null` when the payload
     * lacks the fields this template needs (the handler then skips posting).
     */
    fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        @DrawableRes smallIcon: Int,
        @ColorInt fallbackTintColor: Int?
    ): TemplateRenderResult?
}
