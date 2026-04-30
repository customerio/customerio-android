package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `live_score` template — text-only live update for sports scores.
 *
 * Static: homeTeam{name, logoKey?}, awayTeam{name, logoKey?}, sport (ignored —
 * Android can't change layout per sport without custom views), leagueLogoKey (opt).
 * Dynamic: homeScore/awayScore (int), period (req), clock (opt),
 * statusMessage (opt — overrides body for special situations).
 *
 * Limitation: Android exposes a single large-icon slot; we use [leagueLogoKey].
 * Per-team logos are not rendered.
 */
internal object LiveScoreTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.LIVE_SCORE

    override fun render(
        context: Context,
        payload: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val homeTeam = payload.optJSONObject("homeTeam")
        val awayTeam = payload.optJSONObject("awayTeam")
        val homeName = homeTeam?.optString("name").orEmpty()
        val awayName = awayTeam?.optString("name").orEmpty()
        val homeScore = payload.optInt("homeScore", 0)
        val awayScore = payload.optInt("awayScore", 0)
        val period = payload.optString("period")
        val clock = payload.optString("clock").takeIf { it.isNotEmpty() }
        val statusMessage = payload.optString("statusMessage").takeIf { it.isNotEmpty() }
        val leagueLogoKey = payload.optString("leagueLogoKey").takeIf { it.isNotEmpty() }

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
