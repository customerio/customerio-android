package io.customer.messagingpush

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.extensions.getDrawableByName
import io.customer.messagingpush.extensions.toColorOrNull
import io.customer.messagingpush.util.BitmapDownloader
import io.customer.sdk.core.di.SDKComponent
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Handles live notification creation, updates, and dismissal.
 *
 * Live notifications are ongoing notifications that can be updated in-place
 * (the Android counterpart of iOS Live Activities). They use a stable
 * notification ID derived from [ACTIVITY_ID_KEY] so successive pushes
 * replace the previous notification rather than creating new ones.
 *
 * Payload schema (iOS-style split):
 * - [ACTIVITY_ID_KEY] — stable id
 * - [EVENT_KEY] — "start" | "update" | "end"
 * - [ACTIVITY_TYPE_KEY] — "progress" | "countdown" | "text"
 * - [ATTRIBUTES_KEY] — JSON string: structural/static data (progress_max, icons, etc.)
 * - [CONTENT_STATE_KEY] — JSON string: dynamic data (title, body, progress, subtext, etc.)
 */
internal class LiveNotificationHandler(
    private val bundle: Bundle
) {

    companion object {
        const val ACTIVITY_ID_KEY = "activity_id"
        const val EVENT_KEY = "event"
        const val ACTIVITY_TYPE_KEY = "activity_type"
        const val CONTENT_STATE_KEY = "content_state"
        const val ATTRIBUTES_KEY = "attributes"

        private const val TYPE_PROGRESS = "progress"
        private const val TYPE_COUNTDOWN = "countdown"

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
        val activityId = bundle.getString(ACTIVITY_ID_KEY) ?: return
        val event = bundle.getString(EVENT_KEY) ?: "update"
        val activityType = bundle.getString(ACTIVITY_TYPE_KEY) ?: TYPE_PROGRESS

        val attributes = parseJson(ATTRIBUTES_KEY, bundle.getString(ATTRIBUTES_KEY))
        val contentState = parseJson(CONTENT_STATE_KEY, bundle.getString(CONTENT_STATE_KEY))

        // Structural fields (attributes — stable for the life of the activity)
        val progressMax = attributes.optInt("progress_max", 100)
        val segmentsJson = attributes.optString("segments").takeIf { it.isNotEmpty() }
        val startIconRes = context.getDrawableByName(attributes.optString("start_icon").takeIf { it.isNotEmpty() })
        val endIconRes = context.getDrawableByName(attributes.optString("end_icon").takeIf { it.isNotEmpty() })
        val trackerIconRes = context.getDrawableByName(attributes.optString("tracker_icon").takeIf { it.isNotEmpty() })
        val largeIconUrl = attributes.optString("large_icon").takeIf { it.isNotEmpty() }
        val colorized = attributes.optBoolean("colorized", false)
        val dismissDelay = attributes.optLong("dismiss_delay", DEFAULT_DISMISS_DELAY_MS)

        // Dynamic fields (content_state — updated on every event)
        val title = contentState.optString("title")
        val body = contentState.optString("body")
        val subText = contentState.optString("subtext").takeIf { it.isNotEmpty() }
        val backgroundColor = contentState.optString("color").takeIf { it.isNotEmpty() }?.toColorOrNull()
        val actionsJson = contentState.optString("actions").takeIf { it.isNotEmpty() }
        val progress = contentState.optInt("progress", 0)
        val pointsJson = contentState.optString("points").takeIf { it.isNotEmpty() }
        val countdownUntil = if (activityType == TYPE_COUNTDOWN && contentState.has("countdown_until")) {
            contentState.optLong("countdown_until").takeIf { it > 0 }
        } else {
            null
        }

        val showProgress = activityType == TYPE_PROGRESS

        val largeIcon: Bitmap? = largeIconUrl?.let { url -> downloadBitmap(url) }

        val notifId = activityId.hashCode() and 0x7FFFFFFF

        bundle.putInt(CustomerIOPushNotificationHandler.NOTIFICATION_REQUEST_CODE, notifId)

        val payload = CustomerIOParsedPushPayload(
            extras = Bundle(bundle),
            deepLink = bundle.getString(CustomerIOPushNotificationHandler.DEEP_LINK_KEY),
            cioDeliveryId = deliveryId,
            cioDeliveryToken = deliveryToken,
            title = title,
            body = body,
            activityId = activityId
        )

        val pendingIntent = createIntentForNotificationClick(context, notifId, payload)

        val actions = parseActions(context, actionsJson, activityId, payload)

        val notification = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
                val params = Api36LiveNotificationParams(
                    context = context,
                    channelId = channelId,
                    title = title,
                    body = body,
                    subText = subText,
                    smallIcon = smallIcon,
                    accentColor = backgroundColor ?: tintColor,
                    segmentsJson = segmentsJson,
                    pointsJson = pointsJson,
                    progress = progress,
                    progressMax = progressMax,
                    startIconRes = startIconRes,
                    endIconRes = endIconRes,
                    trackerIconRes = trackerIconRes,
                    pendingIntent = pendingIntent,
                    countdownUntil = countdownUntil,
                    largeIcon = largeIcon,
                    actions = actions,
                    showProgress = showProgress
                )
                Api36LiveNotificationBuilder.build(params)
            }
            else -> {
                val params = BasicNotificationParams(
                    context = context,
                    channelId = channelId,
                    title = title,
                    body = body,
                    subText = subText,
                    smallIcon = smallIcon,
                    tintColor = tintColor,
                    backgroundColor = backgroundColor,
                    colorized = colorized,
                    progress = progress,
                    progressMax = progressMax,
                    pendingIntent = pendingIntent,
                    countdownUntil = countdownUntil,
                    largeIcon = largeIcon,
                    actions = actions,
                    showProgress = showProgress
                )
                BasicNotificationBuilder.build(params)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            SDKComponent.logger.error(
                "Live notification not shown: POST_NOTIFICATIONS permission not granted. " +
                    "The host app must request this permission on Android 13+."
            )
            return
        }

        notificationManager.notify(activityId, notifId, notification)

        if (event == EVENT_END) {
            Handler(Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(activityId, notifId)
            }, dismissDelay)
        }
    }

    private fun parseJson(key: String, raw: String?): JSONObject {
        if (raw.isNullOrEmpty()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            SDKComponent.logger.error("Failed to parse live notification '$key' JSON: ${e.message}")
            JSONObject()
        }
    }

    private fun downloadBitmap(imageUrl: String): Bitmap? = BitmapDownloader.download(imageUrl)

    private fun parseActions(
        context: Context,
        actionsJson: String?,
        activityId: String,
        payload: CustomerIOParsedPushPayload
    ): List<ActionData> {
        if (actionsJson.isNullOrBlank()) return emptyList()

        val actions = mutableListOf<ActionData>()
        try {
            val array = JSONArray(actionsJson)
            val count = minOf(array.length(), 3) // Android supports max 3 action buttons
            for (i in 0 until count) {
                val obj = array.getJSONObject(i)
                val label = obj.optString("label", "").takeIf { it.isNotEmpty() } ?: continue
                val link = obj.optString("link", "")

                val actionPayload = CustomerIOParsedPushPayload(
                    extras = payload.extras,
                    deepLink = link.takeIf { it.isNotEmpty() },
                    cioDeliveryId = payload.cioDeliveryId,
                    cioDeliveryToken = payload.cioDeliveryToken,
                    title = payload.title,
                    body = payload.body,
                    activityId = activityId
                )

                val requestCode = (activityId.hashCode() + i + 1) and 0x7FFFFFFF
                val pendingIntent = createIntentForNotificationClick(context, requestCode, actionPayload)

                actions.add(ActionData(label = label, link = link, pendingIntent = pendingIntent))
            }
        } catch (e: JSONException) {
            SDKComponent.logger.error("Failed to parse live notification actions JSON: ${e.message}")
        }
        return actions
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
