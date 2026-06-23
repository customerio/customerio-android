package io.customer.messagingpush.livenotification.template

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * Renders a single live-notification template.
 *
 * Each template owns its typed schema (e.g. `deliverytracking` knows about
 * `orderId`, `stepCurrent`, `statusImageKey`). The handler dispatches to a
 * concrete subtype via [TemplateRegistry.find] using the `activity_type` key
 * from the FCM envelope.
 *
 * All template fields arrive flattened in a single [data] object alongside the
 * envelope keys (unlike iOS, Android does not split static `attributes` from
 * dynamic `content-state`). Each template reads the fields it documents.
 *
 * Sealed and `internal` — the v1 closed set of templates is the only path.
 * Adding a template means adding a subtype to this hierarchy and an entry to
 * [TemplateRegistry].
 */
internal sealed interface LiveNotificationTemplate {
    val name: String

    /**
     * Renders [data] into a [TemplateRenderResult], or returns `null` when the
     * payload is missing the fields this template needs to show anything
     * meaningful (e.g. content arrived nested/empty rather than flattened). The
     * handler treats `null` as "don't post" and logs an error instead of showing
     * a blank notification.
     */
    fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        @DrawableRes smallIcon: Int,
        @ColorInt fallbackTintColor: Int?
    ): TemplateRenderResult?
}
