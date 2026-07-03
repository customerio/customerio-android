package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `deliverytracking` template — segmented progress bar over delivery stages.
 *
 * Freeform slots (rendered verbatim, never composed): `header` (opt),
 * `title` (req), `subtitle` (opt — recipient/driver/detail).
 * Typed: `image` (opt), `stepCurrent`/`stepTotal` (progress, req),
 * `estimatedArrival` (opt, epoch ms), `statusColor` (opt, hex),
 * `staleMessage` (opt).
 */
internal object DeliveryTrackingTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.DELIVERY_TRACKING

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult? {
        val header = data.optStringNonEmpty(DeliveryTrackingFields.HEADER)
        val title = data.optString(DeliveryTrackingFields.TITLE)
        val subtitle = data.optStringNonEmpty(DeliveryTrackingFields.SUBTITLE)
        val image = data.optStringNonEmpty(DeliveryTrackingFields.IMAGE)
        val stepCurrent = data.optInt(DeliveryTrackingFields.STEP_CURRENT, 0)
        val stepTotal = data.optInt(DeliveryTrackingFields.STEP_TOTAL, 1).coerceAtLeast(1)
        val estimatedArrival = data.optLong(DeliveryTrackingFields.ESTIMATED_ARRIVAL).takeIf { it > 0 }
        val statusColor = data.optColorInt(DeliveryTrackingFields.STATUS_COLOR)
        val staleMessage = data.optStringNonEmpty(DeliveryTrackingFields.STALE_MESSAGE)

        // No usable content (required `title` missing / not flattened): don't render a blank notification.
        if (title.isBlank()) {
            return null
        }

        val segments = List(stepTotal) { SegmentSpec(length = 1) }

        return TemplateRenderResult(
            title = title,
            // A stale push carries `staleMessage` as the current state's message; show it verbatim.
            body = staleMessage ?: subtitle.orEmpty(),
            subText = header,
            largeIcon = TemplateAssets.resolveBitmap(context, image),
            accentColor = statusColor ?: branding?.accentColor ?: fallbackTintColor,
            colorized = false,
            showProgress = true,
            progress = stepCurrent.coerceIn(0, stepTotal),
            progressMax = stepTotal,
            segments = segments,
            points = emptyList(),
            startIconRes = null,
            endIconRes = null,
            trackerIconRes = null,
            countdownUntil = estimatedArrival,
            deepLink = null
        )
    }
}
