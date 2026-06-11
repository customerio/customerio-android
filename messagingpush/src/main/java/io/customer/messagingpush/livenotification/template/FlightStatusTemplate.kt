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
        val flightNumber = data.optString(FlightStatusFields.FLIGHT_NUMBER)
        val origin = data.optJSONObject(FlightStatusFields.ORIGIN)
        val destination = data.optJSONObject(FlightStatusFields.DESTINATION)
        val originCode = origin?.optString(AirportFields.CODE).orEmpty()
        val destinationCode = destination?.optString(AirportFields.CODE).orEmpty()

        val statusMessage = data.optString(FlightStatusFields.STATUS_MESSAGE)
        val gate = data.optStringNonEmpty(FlightStatusFields.GATE)
        val terminal = data.optStringNonEmpty(FlightStatusFields.TERMINAL)
        val scheduledDeparture = data.optLong(FlightStatusFields.SCHEDULED_DEPARTURE).takeIf { it > 0 }
        val estimatedArrival = data.optLong(FlightStatusFields.ESTIMATED_ARRIVAL).takeIf { it > 0 }
        val progressFractionRaw =
            if (data.has(FlightStatusFields.PROGRESS_FRACTION)) data.optDouble(FlightStatusFields.PROGRESS_FRACTION) else Double.NaN
        val progressFraction = progressFractionRaw.takeIf { !it.isNaN() }
        val delayMinutes = data.optInt(FlightStatusFields.DELAY_MINUTES, 0).takeIf { it > 0 }

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
