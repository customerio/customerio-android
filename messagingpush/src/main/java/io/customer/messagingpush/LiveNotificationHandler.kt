package io.customer.messagingpush

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.pushModuleConfig
import io.customer.messagingpush.extensions.getDrawableByName
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.sdk.core.di.SDKComponent
import org.json.JSONException
import org.json.JSONObject

/**
 * Dispatches templated live notifications.
 *
 * Live notifications are ongoing notifications that can be updated in-place
 * (the Android counterpart of iOS Live Activities). Each push declares a
 * `template` (one of the closed set in [TemplateRegistry]) plus a flat JSON
 * `payload` of template-specific fields. Pushes share a stable [ACTIVITY_ID_KEY]
 * so successive updates replace the previous notification rather than creating
 * new ones.
 */
internal class LiveNotificationHandler(
    private val bundle: Bundle
) {

    companion object {
        const val ACTIVITY_ID_KEY = "activity_id"
        const val EVENT_KEY = "event"
        const val TEMPLATE_KEY = "template"
        const val PAYLOAD_KEY = "payload"

        private const val EVENT_END = "end"

        private const val DEFAULT_DISMISS_DELAY_MS = 3000L
    }

    fun handle(
        context: Context,
        deliveryId: String,
        deliveryToken: String,
        @DrawableRes smallIcon: Int,
        @ColorInt tintColor: Int?,
        channelId: String,
        notificationManager: NotificationManager
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            SDKComponent.logger.error(
                "POST_NOTIFICATIONS permission not granted; live notification will be dropped by the system. " +
                    "The host app must request this permission on Android 13+."
            )
        }

        val activityId = bundle.getString(ACTIVITY_ID_KEY) ?: return
        val templateName = bundle.getString(TEMPLATE_KEY)
        val template = TemplateRegistry.find(templateName)
        if (template == null) {
            SDKComponent.logger.error(
                "Unknown live notification template '$templateName'; dropping push for activity '$activityId'."
            )
            return
        }

        val payload = parsePayload(bundle.getString(PAYLOAD_KEY))
        val branding = SDKComponent.pushModuleConfig.liveNotificationBranding
        val effectiveSmallIcon = resolveSmallIcon(context, branding, smallIcon)

        val result = template.render(
            context = context,
            payload = payload,
            branding = branding,
            smallIcon = effectiveSmallIcon,
            fallbackTintColor = tintColor
        )

        val notifId = activityId.hashCode() and 0x7FFFFFFF
        val event = bundle.getString(EVENT_KEY) ?: "update"

        if (result.cancelImmediately) {
            notificationManager.cancel(activityId, notifId)
            return
        }

        bundle.putInt(CustomerIOPushNotificationHandler.NOTIFICATION_REQUEST_CODE, notifId)
        val parsedPayload = CustomerIOParsedPushPayload(
            extras = Bundle(bundle),
            deepLink = result.deepLink ?: bundle.getString(CustomerIOPushNotificationHandler.DEEP_LINK_KEY),
            cioDeliveryId = deliveryId,
            cioDeliveryToken = deliveryToken,
            title = result.title,
            body = result.body,
            activityId = activityId
        )
        val pendingIntent = createIntentForNotificationClick(context, notifId, parsedPayload)

        val notification = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
                val params = Api36LiveNotificationParams(
                    context = context,
                    channelId = channelId,
                    title = result.title,
                    body = result.body,
                    subText = result.subText,
                    smallIcon = effectiveSmallIcon,
                    accentColor = result.accentColor,
                    segments = result.segments,
                    points = result.points,
                    progress = result.progress,
                    progressMax = result.progressMax,
                    startIconRes = result.startIconRes,
                    endIconRes = result.endIconRes,
                    trackerIconRes = result.trackerIconRes,
                    pendingIntent = pendingIntent,
                    countdownUntil = result.countdownUntil,
                    largeIcon = result.largeIcon,
                    showProgress = result.showProgress
                )
                Api36LiveNotificationBuilder.build(params)
            }
            else -> {
                val params = BasicNotificationParams(
                    context = context,
                    channelId = channelId,
                    title = result.title,
                    body = result.body,
                    subText = result.subText,
                    smallIcon = effectiveSmallIcon,
                    accentColor = result.accentColor,
                    colorized = result.colorized,
                    progress = result.progress,
                    progressMax = result.progressMax,
                    pendingIntent = pendingIntent,
                    countdownUntil = result.countdownUntil,
                    largeIcon = result.largeIcon,
                    showProgress = result.showProgress
                )
                BasicNotificationBuilder.build(params)
            }
        }

        notificationManager.notify(activityId, notifId, notification)

        if (event == EVENT_END) {
            Handler(Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(activityId, notifId)
            }, DEFAULT_DISMISS_DELAY_MS)
        }
    }

    private fun parsePayload(raw: String?): JSONObject {
        if (raw.isNullOrEmpty()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            SDKComponent.logger.error("Failed to parse live notification payload JSON: ${e.message}")
            JSONObject()
        }
    }

    @DrawableRes
    private fun resolveSmallIcon(
        context: Context,
        branding: LiveNotificationBranding?,
        fallback: Int
    ): Int {
        val key = branding?.logoDrawableName?.takeIf { it.isNotEmpty() } ?: return fallback
        val normalized = key.replace('-', '_')
        return context.getDrawableByName(normalized) ?: fallback
    }

    private fun createIntentForNotificationClick(
        context: Context,
        requestCode: Int,
        payload: CustomerIOParsedPushPayload
    ): PendingIntent {
        val notifyIntent = Intent(context, NotificationClickReceiverActivity::class.java)
        notifyIntent.putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, payload)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            notifyIntent,
            flags
        )
    }
}
