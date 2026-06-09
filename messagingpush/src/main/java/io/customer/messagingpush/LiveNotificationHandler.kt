package io.customer.messagingpush

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.pushModuleConfig
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.livenotification.template.TemplateAssets
import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.core.di.SDKComponent
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Dispatches templated live notifications.
 *
 * Live notifications are ongoing notifications that can be updated in-place
 * (the Android counterpart of iOS Live Activities). Each push declares an
 * `activity_type` (one of the closed set in [TemplateRegistry], prefixed with
 * `io.customer.liveactivities.`). Unlike iOS, Android does not split static
 * `attributes` from dynamic `content-state`: all template fields arrive
 * flattened at the envelope top level. Pushes share a stable [ACTIVITY_ID_KEY]
 * so successive updates replace the previous notification rather than creating
 * new ones.
 */
internal class LiveNotificationHandler(
    private val bundle: Bundle
) {

    companion object {
        const val ACTIVITY_ID_KEY = "activity_id"
        const val EVENT_KEY = "event"
        const val ACTIVITY_TYPE_KEY = "activity_type"
        const val TIMESTAMP_KEY = "timestamp"
        const val DISMISSAL_DATE_KEY = "dismissal_date"

        private const val EVENT_END = "end"

        /**
         * Live-notification envelope keys that are never template fields.
         * Everything else in the bundle is flattened into the template `data`
         * object.
         *
         * Note: standard-push keys (`title`, `body`, `image`, `link`, …) are
         * intentionally NOT reserved here — they are not part of the live
         * envelope, and reserving them would shadow legitimate template fields
         * of the same name (e.g. CountdownTimer's `title`).
         */
        private val RESERVED_KEYS = setOf(
            ACTIVITY_ID_KEY,
            EVENT_KEY,
            ACTIVITY_TYPE_KEY,
            TIMESTAMP_KEY,
            DISMISSAL_DATE_KEY,
            PushTrackingUtil.DELIVERY_ID_KEY,
            PushTrackingUtil.DELIVERY_TOKEN_KEY
        )
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
        val event = bundle.getString(EVENT_KEY)
        if (event == null) {
            SDKComponent.logger.error(
                "Live notification push for activity '$activityId' is missing '$EVENT_KEY'; dropping."
            )
            return
        }
        val activityType = bundle.getString(ACTIVITY_TYPE_KEY)
        val template = TemplateRegistry.find(activityType)
        if (template == null) {
            SDKComponent.logger.error(
                "Unknown live notification template '$activityType'; dropping push for activity '$activityId'."
            )
            return
        }

        val data = extractData(bundle)
        val branding = SDKComponent.pushModuleConfig.liveNotificationBranding
        val effectiveSmallIcon = resolveSmallIcon(context, branding, smallIcon)

        val result = template.render(
            context = context,
            data = data,
            branding = branding,
            smallIcon = effectiveSmallIcon,
            fallbackTintColor = tintColor
        )

        val notifId = activityId.hashCode() and 0x7FFFFFFF

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
            // Without a dismissal_date the activity is removed immediately.
            // Scheduled dismissal via dismissal_date is added with lifecycle reporting.
            notificationManager.cancel(activityId, notifId)
        }
    }

    /**
     * Collects the flattened template fields from the FCM envelope: every
     * top-level bundle key that is not a [RESERVED_KEYS] envelope key. String
     * values that look like JSON objects/arrays (e.g. `origin`, `homeTeam`) are
     * parsed so templates can read them as nested structures; scalar strings
     * are kept verbatim and coerced on read by `JSONObject.optInt`/`optLong`/etc.
     */
    private fun extractData(bundle: Bundle): JSONObject {
        val data = JSONObject()
        for (key in bundle.keySet()) {
            if (key in RESERVED_KEYS) continue
            val raw = bundle.getString(key) ?: continue
            data.put(key, coerceJsonValue(raw))
        }
        return data
    }

    private fun coerceJsonValue(raw: String): Any {
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> raw
            }
        } catch (e: JSONException) {
            raw
        }
    }

    @DrawableRes
    private fun resolveSmallIcon(
        context: Context,
        branding: LiveNotificationBranding?,
        fallback: Int
    ): Int {
        // Reuses TemplateAssets' kebab→snake normalization + drawable lookup.
        return TemplateAssets.resolveDrawable(context, branding?.logoDrawableName) ?: fallback
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
