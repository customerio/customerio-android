package io.customer.messagingpush.livenotification.template

import android.content.Context
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import org.json.JSONObject

/**
 * `countdowntimer` template — chronometer ticking toward [targetDate].
 *
 * Fields: title (req), heroImageKey (opt), targetDate (req, epoch ms —
 * extendable across pushes), statusMessage (req, label above timer),
 * expiredMessage (opt). Post-target with no expiredMessage means the activity
 * should hide; SDK signals this via [TemplateRenderResult.cancelImmediately].
 */
internal object CountdownTimerTemplate : LiveNotificationTemplate {

    override val name: String = TemplateRegistry.COUNTDOWN_TIMER

    override fun render(
        context: Context,
        data: JSONObject,
        branding: LiveNotificationBranding?,
        smallIcon: Int,
        fallbackTintColor: Int?
    ): TemplateRenderResult {
        val title = data.optString("title")
        val heroImageKey = data.optStringNonEmpty("heroImageKey")
        val targetDate = data.optLong("targetDate").takeIf { it > 0 }
        val statusMessage = data.optString("statusMessage")
        val expiredMessage = data.optStringNonEmpty("expiredMessage")

        val now = System.currentTimeMillis()
        val isPostTarget = targetDate != null && now >= targetDate
        // Server pushed a post-target state with no message: hide the activity.
        val cancelImmediately = isPostTarget && expiredMessage == null

        return TemplateRenderResult(
            title = title,
            body = if (isPostTarget) expiredMessage.orEmpty() else statusMessage,
            subText = null,
            largeIcon = if (cancelImmediately) null else TemplateAssets.resolveBitmap(context, heroImageKey),
            accentColor = if (cancelImmediately) null else (branding?.accentColor ?: fallbackTintColor),
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
