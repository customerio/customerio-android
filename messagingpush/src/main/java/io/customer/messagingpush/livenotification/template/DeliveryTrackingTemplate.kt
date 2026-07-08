package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `deliverytracking` template — segmented progress bar over delivery stages.
 *
 * Freeform slots (rendered verbatim, never composed): `header` (opt),
 * `title` (req), `subtitle` (opt — recipient/driver/detail).
 * Typed: `image` (opt), `progress` {current, total} (req nested object),
 * `estimatedArrival` (opt, epoch seconds), `statusColor` (opt, hex),
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
        // Progress is a nested { current, total } object (matches iOS content-state), not two
        // flat scalars. Absent/partial progress falls back to a 0-of-1 bar.
        val progress = data.optJSONObject(DeliveryTrackingFields.PROGRESS)
        val stepCurrent = progress?.optInt(ProgressFields.CURRENT, 0) ?: 0
        val stepTotal = (progress?.optInt(ProgressFields.TOTAL, 1) ?: 1).coerceAtLeast(1)
        val estimatedArrival = data.optEpochSecondsAsMillis(DeliveryTrackingFields.ESTIMATED_ARRIVAL)
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
