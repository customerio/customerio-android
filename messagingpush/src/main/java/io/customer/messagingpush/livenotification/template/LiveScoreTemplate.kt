package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `livescore` template — text-only live update for sports scores.
 *
 * Freeform slots (rendered verbatim, never composed): `subtitle` (opt
 * bottom-label — e.g. "Starts in 15 Min" / "2nd half · 55:67" / "Final Score").
 * Typed: `homeTeam`/`awayTeam` {name, logo?} (req), `homeScore`/`awayScore`
 * (opt, int, present during/post-game), `image` (opt league/app icon),
 * `statusColor` (opt, hex), `staleMessage` (opt).
 *
 * Team names and scores are structured (typed) fields, so the template renders
 * them into dedicated slots (title = matchup, subText = score) rather than
 * composing a freeform sentence. The freeform `subtitle` is shown verbatim as
 * the body.
 *
 * Logo limitation: a standard Android notification exposes a single large-icon
 * image slot (the two-endpoint icons exist only inside a progress bar, which a
 * score isn't), and we render natively — no custom/RemoteViews. So we can show
 * exactly one logo: the league/app `image` if provided, otherwise a team logo
 * (home, then away). The chosen key resolves through the full asset pipeline
 * (remote URL / registered asset / bundled drawable).
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
        val homeName = homeTeam?.optStringNonEmpty(TeamFields.NAME)
        val awayName = awayTeam?.optStringNonEmpty(TeamFields.NAME)
        val hasHomeScore = data.has(LiveScoreFields.HOME_SCORE) && !data.isNull(LiveScoreFields.HOME_SCORE)
        val hasAwayScore = data.has(LiveScoreFields.AWAY_SCORE) && !data.isNull(LiveScoreFields.AWAY_SCORE)
        val homeScore = data.optInt(LiveScoreFields.HOME_SCORE, 0)
        val awayScore = data.optInt(LiveScoreFields.AWAY_SCORE, 0)
        val subtitle = data.optStringNonEmpty(LiveScoreFields.SUBTITLE)
        val image = data.optStringNonEmpty(LiveScoreFields.IMAGE)
        val homeLogo = homeTeam?.optStringNonEmpty(TeamFields.LOGO)
        val awayLogo = awayTeam?.optStringNonEmpty(TeamFields.LOGO)
        val statusColor = data.optColorInt(LiveScoreFields.STATUS_COLOR)
        val staleMessage = data.optStringNonEmpty(LiveScoreFields.STALE_MESSAGE)

        // No usable content (required teams missing / not flattened): don't render a blank notification.
        if (homeName == null && awayName == null) {
            return null
        }

        // Title = matchup (team names in their own slots). Score, when present, is a
        // typed numeric slot rendered as subText — not a composed freeform sentence.
        val title = listOfNotNull(homeName, awayName).joinToString(" - ")
        val subText = if (hasHomeScore || hasAwayScore) "$homeScore–$awayScore" else null

        // Single native image slot: prefer the league/app image, then a team logo.
        val largeIcon = TemplateAssets.resolveBitmap(context, image)
            ?: TemplateAssets.resolveBitmap(context, homeLogo)
            ?: TemplateAssets.resolveBitmap(context, awayLogo)

        return TemplateRenderResult(
            title = title,
            // A stale push carries `staleMessage` as the current state's message; show it verbatim.
            body = staleMessage ?: subtitle.orEmpty(),
            subText = subText,
            largeIcon = largeIcon,
            accentColor = statusColor ?: branding?.accentColor ?: fallbackTintColor,
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
