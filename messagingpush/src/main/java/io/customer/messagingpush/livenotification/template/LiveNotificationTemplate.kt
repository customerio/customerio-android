package io.customer.messagingpush.livenotification.template

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * Renders a single live-notification template.
 *
 * Each template owns its typed schema (e.g. `delivery_tracking` knows about
 * `orderId`, `stepCurrent`, `statusImageKey`). The handler dispatches to a
 * concrete subtype via [TemplateRegistry.find] using the `activity_type` key
 * from the FCM envelope.
 *
 * Static fields arrive in the `attributes` JSON object; dynamic fields arrive
 * in the `content_state` JSON object. Strict slotting is enforced — each
 * template reads each documented field only from its designated slot.
 *
 * Sealed and `internal` — the v1 closed set of templates is the only path.
 * Adding a template means adding a subtype to this hierarchy and an entry to
 * [TemplateRegistry].
 */
internal sealed interface LiveNotificationTemplate {
    val name: String

    fun render(
        context: Context,
        attributes: JSONObject,
        contentState: JSONObject,
        branding: LiveNotificationBranding?,
        @DrawableRes smallIcon: Int,
        @ColorInt fallbackTintColor: Int?
    ): TemplateRenderResult
}
