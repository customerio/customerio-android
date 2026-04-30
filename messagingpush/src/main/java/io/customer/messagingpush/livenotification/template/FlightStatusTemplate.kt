package io.customer.messagingpush.livenotification.template

import android.content.Context
import android.graphics.Color
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `flight_status` template — flight progress with optional in-flight progress bar.
 *
 * Static: flightNumber, origin{code, city}, destination{code, city}.
 * Dynamic: statusMessage (req), gate (opt), terminal (opt),
 * scheduledDeparture/estimatedArrival (req, epoch ms), progressFraction (opt, 0–1),
 * delayMinutes (opt).
 */
internal object FlightStatusTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.FLIGHT_STATUS

    private const val DELAY_RED: Int = -0x33ccd0 // #CC3330

    override fun render(
        context: Context,
        payload: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val flightNumber = payload.optString("flightNumber")
        val origin = payload.optJSONObject("origin")
        val destination = payload.optJSONObject("destination")
        val originCode = origin?.optString("code").orEmpty()
        val destinationCode = destination?.optString("code").orEmpty()

        val statusMessage = payload.optString("statusMessage")
        val gate = payload.optString("gate").takeIf { it.isNotEmpty() }
        val terminal = payload.optString("terminal").takeIf { it.isNotEmpty() }
        val scheduledDeparture = payload.optLong("scheduledDeparture").takeIf { it > 0 }
        val estimatedArrival = payload.optLong("estimatedArrival").takeIf { it > 0 }
        val progressFractionRaw =
            if (payload.has("progressFraction")) payload.optDouble("progressFraction") else Double.NaN
        val progressFraction = progressFractionRaw.takeIf { !it.isNaN() }
        val delayMinutes = payload.optInt("delayMinutes", 0).takeIf { it > 0 }

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
