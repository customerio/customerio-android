package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `flightstatus` template — flight progress with optional in-flight progress bar.
 *
 * Freeform slots (rendered verbatim, never composed): `header` (opt),
 * `status` (opt short label), `title` (req contextual line),
 * `subtitle` (opt — gate/terminal/zone/bag).
 * Typed: `origin`/`destination` {code, city} (req), `scheduledDeparture`/
 * `estimatedArrival` (req, epoch seconds), `progressFraction` (opt, 0–1),
 * `statusColor` (opt, hex), `staleMessage` (opt).
 */
internal object FlightStatusTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.FLIGHT_STATUS

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult? {
        val title = data.optString(FlightStatusFields.TITLE)
        val status = data.optStringNonEmpty(FlightStatusFields.STATUS)
        val subtitle = data.optStringNonEmpty(FlightStatusFields.SUBTITLE)

        val scheduledDeparture = data.optEpochSecondsAsMillis(FlightStatusFields.SCHEDULED_DEPARTURE)
        val estimatedArrival = data.optEpochSecondsAsMillis(FlightStatusFields.ESTIMATED_ARRIVAL)
        val progressFractionRaw =
            if (data.has(FlightStatusFields.PROGRESS_FRACTION)) data.optDouble(FlightStatusFields.PROGRESS_FRACTION) else Double.NaN
        val progressFraction = progressFractionRaw.takeIf { !it.isNaN() }
        val statusColor = data.optColorInt(FlightStatusFields.STATUS_COLOR)
        val staleMessage = data.optStringNonEmpty(FlightStatusFields.STALE_MESSAGE)

        // No usable content (required `title` missing / not flattened): don't render a blank notification.
        if (title.isBlank()) {
            return null
        }

        val showProgress = progressFraction != null
        val progress = progressFraction
            ?.coerceIn(0.0, 1.0)
            ?.let { (it * 100).toInt() }
            ?: 0
        val countdownUntil = if (showProgress) estimatedArrival else scheduledDeparture

        return TemplateRenderResult(
            title = title,
            // A stale push carries `staleMessage` as the current state's message; show it verbatim.
            body = staleMessage ?: status.orEmpty(),
            subText = subtitle,
            largeIcon = null,
            accentColor = statusColor ?: branding?.accentColor ?: fallbackTintColor,
            colorized = false,
            showProgress = showProgress,
            progress = progress,
            progressMax = 100,
            segments = emptyList(),
            points = emptyList(),
            startIconRes = null,
            endIconRes = null,
            trackerIconRes = null,
            countdownUntil = countdownUntil,
            deepLink = null
        )
    }
}
