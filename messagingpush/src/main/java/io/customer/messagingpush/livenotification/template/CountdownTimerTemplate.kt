package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `countdown_timer` template — chronometer ticking toward [targetDate].
 *
 * Static: title (req), heroImageKey (opt).
 * Dynamic: targetDate (req, epoch ms — extendable across pushes), statusMessage
 * (req, label above timer), expiredMessage (opt). Post-target with no
 * expiredMessage means the activity should hide; SDK signals this via
 * [TemplateRenderResult.cancelImmediately].
 */
internal object CountdownTimerTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.COUNTDOWN_TIMER

    override fun render(
        context: Context,
        payload: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val title = payload.optString("title")
        val heroImageKey = payload.optString("heroImageKey").takeIf { it.isNotEmpty() }
        val targetDate = payload.optLong("targetDate").takeIf { it > 0 }
        val statusMessage = payload.optString("statusMessage")
        val expiredMessage = payload.optString("expiredMessage").takeIf { it.isNotEmpty() }

        val now = System.currentTimeMillis()
        val isPostTarget = targetDate != null && now >= targetDate

        if (isPostTarget && expiredMessage == null) {
            // Server pushed a post-target state with no message: hide the activity.
            return TemplateRenderResult(
                title = title,
                body = "",
                subText = null,
                largeIcon = null,
                accentColor = null,
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
                deepLink = null,
                cancelImmediately = true
            )
        }

        val body = if (isPostTarget) expiredMessage.orEmpty() else statusMessage
        val countdownUntil = if (isPostTarget) null else targetDate

        return TemplateRenderResult(
            title = title,
            body = body,
            subText = null,
            largeIcon = TemplateAssets.resolveBitmap(context, heroImageKey),
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
            countdownUntil = countdownUntil,
            deepLink = null
        )
    }
}
