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
 * Logo limitation: a standard Android notification exposes a single large-icon
 * image slot (the two-endpoint icons exist only inside a progress bar, which a
 * score isn't), and we render natively — no custom/RemoteViews. So we can show
 * exactly one logo: the league logo if provided, otherwise a team logo (home,
 * then away). Showing both team logos side-by-side would require custom views
 * and is intentionally not done. The chosen key resolves through the full asset
 * pipeline (remote URL / registered asset / bundled drawable).
 */
internal object LiveScoreTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.LIVE_SCORE

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult? {
        val homeTeam = data.optJSONObject(LiveScoreFields.HOME_TEAM)
        val awayTeam = data.optJSONObject(LiveScoreFields.AWAY_TEAM)
        val homeName = homeTeam?.optString(TeamFields.NAME).orEmpty()
        val awayName = awayTeam?.optString(TeamFields.NAME).orEmpty()
        val homeScore = data.optInt(LiveScoreFields.HOME_SCORE, 0)
        val awayScore = data.optInt(LiveScoreFields.AWAY_SCORE, 0)
        val period = data.optString(LiveScoreFields.PERIOD)
        val clock = data.optStringNonEmpty(LiveScoreFields.CLOCK)
        val statusMessage = data.optStringNonEmpty(LiveScoreFields.STATUS_MESSAGE)
        val leagueLogoKey = data.optStringNonEmpty(LiveScoreFields.LEAGUE_LOGO_KEY)
        val homeLogoKey = homeTeam?.optStringNonEmpty(TeamFields.LOGO_KEY)
        val awayLogoKey = awayTeam?.optStringNonEmpty(TeamFields.LOGO_KEY)

        // No usable content (fields missing / not flattened): don't render a blank notification.
        if (homeName.isBlank() && awayName.isBlank() && statusMessage == null && period.isBlank() && clock == null) {
            return null
        }

        val title = "$homeName $homeScore - $awayScore $awayName"
        val body = statusMessage
            ?: clock?.let { "$period · $it" }
            ?: period

        // Single native image slot: prefer the league logo, then a team logo.
        val largeIcon = TemplateAssets.resolveBitmap(context, leagueLogoKey)
            ?: TemplateAssets.resolveBitmap(context, homeLogoKey)
            ?: TemplateAssets.resolveBitmap(context, awayLogoKey)

        return TemplateRenderResult(
            title = title,
            body = body,
            subText = null,
            largeIcon = largeIcon,
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
