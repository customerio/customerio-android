package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `flightstatus` template — flight progress with optional in-flight progress bar.
 *
 * Fields: flightNumber, origin{code, city}, destination{code, city},
 * statusMessage (req), gate (opt), terminal (opt),
 * scheduledDeparture/estimatedArrival (req, epoch ms), progressFraction (opt, 0–1),
 * delayMinutes (opt).
 */
internal object FlightStatusTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.FLIGHT_STATUS

    private const val DELAY_RED: Int = -0x33ccd0 // #CC3330

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val flightNumber = data.optString("flightNumber")
        val origin = data.optJSONObject("origin")
        val destination = data.optJSONObject("destination")
        val originCode = origin?.optString("code").orEmpty()
        val destinationCode = destination?.optString("code").orEmpty()

        val statusMessage = data.optString("statusMessage")
        val gate = data.optStringNonEmpty("gate")
        val terminal = data.optStringNonEmpty("terminal")
        val scheduledDeparture = data.optLong("scheduledDeparture").takeIf { it > 0 }
        val estimatedArrival = data.optLong("estimatedArrival").takeIf { it > 0 }
        val progressFractionRaw =
            if (data.has("progressFraction")) data.optDouble("progressFraction") else Double.NaN
        val progressFraction = progressFractionRaw.takeIf { !it.isNaN() }
        val delayMinutes = data.optInt("delayMinutes", 0).takeIf { it > 0 }

        val title = "$flightNumber · $originCode → $destinationCode"
        val subText = "Gate ${gate ?: "TBA"} · Terminal ${terminal ?: "TBA"}"
        val body = if (delayMinutes != null) {
            "$statusMessage · Delayed $delayMinutes min"
        } else {
            statusMessage
        }

        val showProgress = progressFraction != null
        val progress = progressFraction
            ?.coerceIn(0.0, 1.0)
            ?.let { (it * 100).toInt() }
            ?: 0
        val countdownUntil = if (showProgress) estimatedArrival else scheduledDeparture
        val accentColor = when {
            delayMinutes != null -> DELAY_RED
            else -> branding?.accentColor ?: fallbackTintColor
        }

        return TemplateRenderResult(
            title = title,
            body = body,
            subText = subText,
            largeIcon = null,
            accentColor = accentColor,
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
