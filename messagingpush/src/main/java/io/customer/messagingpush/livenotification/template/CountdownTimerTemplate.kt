package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `countdowntimer` template — a status headline over a live countdown to
 * `endTime`, matching iOS `CIOCountdownTimerAttributes`.
 *
 * Freeform slots (rendered verbatim, never composed): `header` (opt, static
 * top-row label), `title` (req, primary status line), `statusMessage` (opt,
 * secondary line). Structured: `endTime` (opt, epoch seconds).
 *
 * While `endTime` is in the future the template renders a live chronometer. The
 * finished state is push-driven: the backend pushes a new content-state with a
 * "done" `title`/`statusMessage` and no `endTime`, so when `endTime` is absent or
 * already past, no chronometer is shown (the clock doesn't disappear on its own).
 *
 * Styling is branding-only: no image/statusColor. The accent falls back to the
 * app branding accent then the FCM default tint; the large icon comes from the
 * branding logo (applied by the handler, not here).
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

        // No usable content (required `title` missing / not flattened): don't render a blank
        // notification. The finished-state push always carries a "done" title, so this never
        // blocks the terminal state.
        if (title.isBlank()) {
            return null
        }

        // Finished state is push-driven: an absent/past endTime means "no live timer", so we
        // simply omit the chronometer and show title + statusMessage.
        val now = System.currentTimeMillis()
        val isCountingDown = endTime != null && now < endTime

        return TemplateRenderResult(
            title = title,
            body = statusMessage.orEmpty(),
            subText = header,
            // Branding-only: no per-push image. The handler fills largeIcon from the branding
            // logo when this is null.
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
