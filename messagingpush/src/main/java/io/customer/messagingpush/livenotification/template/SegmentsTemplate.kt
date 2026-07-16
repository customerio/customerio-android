package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `segments` template — a status headline over a discrete, multi-step progress
 * bar ("step N of M"), matching iOS `CIOSegmentsAttributes`.
 *
 * Freeform slots (rendered verbatim, never composed): `header` (opt, static
 * top-row label), `status` (req, primary status line), `substatus` (opt,
 * secondary line). Structured: `segmentsTotal` (Int, bar size) and
 * `segmentsComplete` (Int, filled count, clamped to `0..segmentsTotal`).
 *
 * `trailingText` (opt) is iOS's Dynamic Island trailing-edge readout (e.g.
 * `"5 min"`); Android notifications expose no distinct trailing slot, so it is
 * surfaced verbatim in the body line only when `substatus` is absent (see [render]).
 *
 * Styling is branding-only: no image/statusColor. The accent falls back to the
 * app branding accent then the FCM default tint; the large icon comes from the
 * branding logo (applied by the handler, not here).
 */
internal object SegmentsTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.SEGMENTS

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult? {
        val header = data.optStringNonEmpty(SegmentsFields.HEADER)
        val status = data.optString(SegmentsFields.STATUS)
        val substatus = data.optStringNonEmpty(SegmentsFields.SUBSTATUS)
        val trailingText = data.optStringNonEmpty(SegmentsFields.TRAILING_TEXT)
        // Flat integer counts (matches iOS content-state), not a nested progress object.
        val segmentsTotal = data.optInt(SegmentsFields.SEGMENTS_TOTAL, 1).coerceAtLeast(1)
        val segmentsComplete = data.optInt(SegmentsFields.SEGMENTS_COMPLETE, 0)

        // No usable content (required `status` missing / not flattened): don't render a blank notification.
        if (status.isBlank()) {
            return null
        }

        // Colour the segments with the brand accent so the progress bar itself reads as branded:
        // the system renders the completed portion (up to `progress`) solid and dims the upcoming
        // portion, so a single accent gives the filled/faded split (mirrors the iOS segmented bar).
        val accent = branding?.accentColor ?: fallbackTintColor
        val segments = List(segmentsTotal) { SegmentSpec(length = 1, color = accent) }

        return TemplateRenderResult(
            title = status,
            // `substatus` owns the body line. `trailingText` has no dedicated Android
            // notification slot (it is an iOS Dynamic Island trailing-edge concept), so it is
            // surfaced verbatim in the body only when `substatus` is absent — never composed.
            body = substatus ?: trailingText.orEmpty(),
            subText = header,
            // Branding-only: no per-push image. The handler fills largeIcon from the branding
            // logo when this is null.
            largeIcon = null,
            accentColor = accent,
            colorized = false,
            showProgress = true,
            progress = segmentsComplete.coerceIn(0, segmentsTotal),
            progressMax = segmentsTotal,
            segments = segments,
            points = emptyList(),
            startIconRes = null,
            endIconRes = null,
            trackerIconRes = null,
            countdownUntil = null,
            deepLink = null
        )
    }
}
