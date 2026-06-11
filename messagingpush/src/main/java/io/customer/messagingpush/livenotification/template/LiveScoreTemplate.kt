package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `livescore` template — text-only live update for sports scores.
 *
 * Fields: homeTeam{name, logoKey?}, awayTeam{name, logoKey?}, sport (ignored —
 * Android can't change layout per sport without custom views), leagueLogoKey (opt),
 * homeScore/awayScore (int), period (req), clock (opt),
 * statusMessage (opt — overrides body for special situations).
 *
 * Limitation: Android exposes a single large-icon slot; we use [leagueLogoKey].
 * Per-team logos are not rendered.
 */
internal object LiveScoreTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.LIVE_SCORE

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val homeTeam = data.optJSONObject("homeTeam")
        val awayTeam = data.optJSONObject("awayTeam")
        val homeName = homeTeam?.optString("name").orEmpty()
        val awayName = awayTeam?.optString("name").orEmpty()
        val homeScore = data.optInt("homeScore", 0)
        val awayScore = data.optInt("awayScore", 0)
        val period = data.optString("period")
        val clock = data.optStringNonEmpty("clock")
        val statusMessage = data.optStringNonEmpty("statusMessage")
        val leagueLogoKey = data.optStringNonEmpty("leagueLogoKey")

        val title = "$homeName $homeScore - $awayScore $awayName"
        val body = statusMessage
            ?: clock?.let { "$period · $it" }
            ?: period

        return TemplateRenderResult(
            title = title,
            body = body,
            subText = null,
            largeIcon = TemplateAssets.resolveBitmap(context, leagueLogoKey),
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
            countdownUntil = null,
            deepLink = null
        )
    }
}
