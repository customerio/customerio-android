package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `countdowntimer` template — a status headline over a live countdown to
 * `endTime` (epoch seconds), matching iOS `CIOCountdownTimerAttributes`.
 */
internal object CountdownTimerTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.COUNTDOWN_TIMER

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult? {
        val header = data.optStringNonEmpty(CountdownTimerFields.HEADER)
        val title = data.optString(CountdownTimerFields.TITLE)
        val statusMessage = data.optStringNonEmpty(CountdownTimerFields.STATUS_MESSAGE)
        val endTime = data.optEpochSecondsAsMillis(CountdownTimerFields.END_TIME)

        if (title.isBlank()) {
            return null
        }

        val now = System.currentTimeMillis()
        val isCountingDown = endTime != null && now < endTime

        return TemplateRenderResult(
            title = title,
            body = statusMessage.orEmpty(),
            subText = header,
            largeIcon = null,
            accentColor = branding?.accentColor ?: fallbackTintColor,
            colorized = false,
            showProgress = false,
            progress = 0,
            progressMax = 0,
            segments = emptyList(),
            points = emptyList(),
            startIconRes = null,
            endIconRes = null,
            trackerIconRes = null,
            countdownUntil = if (isCountingDown) endTime else null,
            deepLink = null
        )
    }
}
