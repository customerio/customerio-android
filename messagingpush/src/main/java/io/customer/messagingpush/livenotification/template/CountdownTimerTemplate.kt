package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `countdowntimer` template — chronometer ticking toward `targetDate`.
 *
 * Freeform slots (rendered verbatim, never composed): `header` (opt),
 * `title` (req), `subtitle` (req — label above timer, e.g. "Sale ends in"),
 * `expiredMessage` (opt).
 * Typed: `targetDate` (req, epoch ms — extendable across pushes), `image` (opt),
 * `statusColor` (opt, hex), `staleMessage` (opt). Post-target with no
 * `expiredMessage` means the activity should hide; the SDK signals this via
 * [TemplateRenderResult.cancelImmediately].
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
        val subtitle = data.optString(CountdownTimerFields.SUBTITLE)
        val image = data.optStringNonEmpty(CountdownTimerFields.IMAGE)
        val targetDate = data.optLong(CountdownTimerFields.TARGET_DATE).takeIf { it > 0 }
        val expiredMessage = data.optStringNonEmpty(CountdownTimerFields.EXPIRED_MESSAGE)
        val statusColor = data.optColorInt(CountdownTimerFields.STATUS_COLOR)
        val staleMessage = data.optStringNonEmpty(CountdownTimerFields.STALE_MESSAGE)

        // No usable content (required `title` missing / not flattened): don't render a blank notification.
        // A real countdown always carries a targetDate, so this never blocks a post-target hide.
        if (title.isBlank() && targetDate == null) {
            return null
        }

        val now = System.currentTimeMillis()
        val isPostTarget = targetDate != null && now >= targetDate
        // Server pushed a post-target state with no message: hide the activity.
        val cancelImmediately = isPostTarget && expiredMessage == null

        return TemplateRenderResult(
            title = title,
            // A stale push carries `staleMessage` as the current state's message; show it verbatim.
            body = staleMessage ?: (if (isPostTarget) expiredMessage.orEmpty() else subtitle),
            subText = header,
            largeIcon = if (cancelImmediately) null else TemplateAssets.resolveBitmap(context, image),
            accentColor = if (cancelImmediately) null else (statusColor ?: branding?.accentColor ?: fallbackTintColor),
            colorized = false,
            showProgress = false,
            progress = 0,
            progressMax = 0,
            segments = emptyList(),
            points = emptyList(),
            startIconRes = null,
            endIconRes = null,
            trackerIconRes = null,
            countdownUntil = if (isPostTarget) null else targetDate,
            deepLink = null,
            cancelImmediately = cancelImmediately
        )
    }
}
