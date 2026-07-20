package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `segments` template — a status headline over a discrete, multi-step progress
 * bar, matching iOS `CIOSegmentsAttributes`.
 */
internal object SegmentsTemplate : LiveNotificationTemplate {

    // Upper bound on rendered segments. segmentsTotal comes from an untrusted push
    // payload; without a cap a very large value allocates that many segment views and
    // can exhaust memory while handling the push. Kept in sync with iOS + services.
    private const val MAX_SEGMENTS = 20

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
        val segmentsTotal = data.optInt(SegmentsFields.SEGMENTS_TOTAL, 1).coerceIn(1, MAX_SEGMENTS)
        val segmentsComplete = data.optInt(SegmentsFields.SEGMENTS_COMPLETE, 0)

        if (status.isBlank()) {
            return null
        }

        val accent = branding?.accentColor ?: fallbackTintColor
        val segments = List(segmentsTotal) { SegmentSpec(length = 1, color = accent) }

        return TemplateRenderResult(
            title = status,
            // Android has a single body slot, so trailingText only shows when
            // substatus is absent; when both are set, trailingText is dropped
            // (unlike iOS, which renders it on the progress bar's trailing edge).
            body = substatus ?: trailingText.orEmpty(),
            subText = header,
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
