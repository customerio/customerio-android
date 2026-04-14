package io.customer.messagingpush

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.extensions.getDrawableByName
import io.customer.messagingpush.extensions.toColorOrNull
import io.customer.sdk.core.di.SDKComponent
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

/**
 * Handles live notification creation, updates, and dismissal.
 *
 * Live notifications are ongoing notifications that can be updated in-place
 * (the Android counterpart of iOS Live Activities). They use a stable
 * notification ID derived from [liveNotificationId] so successive pushes
 * replace the previous notification rather than creating new ones.
 */
internal class LiveNotificationHandler(
    private val bundle: Bundle
) {

    companion object {
        // Required — every live notification payload must include these
        const val LIVE_NOTIFICATION_ID_KEY = "cio_live_notification_id"
        const val LIVE_NOTIFICATION_STATUS_KEY = "cio_live_notification_status"
        // title and body come from standard push keys, not live-specific keys

        // Required per type — minimum fields for the notification to be meaningful
        // Progress: type + progress + progress_max (renders a progress bar)
        // Countdown: type + countdown_until (renders a countdown timer)
        // Text: type alone is sufficient (title/body carry the content)
        const val LIVE_NOTIFICATION_TYPE_KEY = "cio_live_notification_type"
        const val LIVE_NOTIFICATION_PROGRESS_KEY = "cio_live_notification_progress"
        const val LIVE_NOTIFICATION_PROGRESS_MAX_KEY = "cio_live_notification_progress_max"
        const val LIVE_NOTIFICATION_COUNTDOWN_UNTIL_KEY = "cio_live_notification_countdown_until"

        // Optional — visual customization (notification works without these)
        const val LIVE_NOTIFICATION_SUBTEXT_KEY = "cio_live_notification_subtext"
        const val LIVE_NOTIFICATION_COLOR_KEY = "cio_live_notification_color"
        const val LIVE_NOTIFICATION_COLORIZED_KEY = "cio_live_notification_colorized"
        const val LIVE_NOTIFICATION_LARGE_ICON_KEY = "cio_live_notification_large_icon"
        const val LIVE_NOTIFICATION_ACTIONS_KEY = "cio_live_notification_actions"
        const val LIVE_NOTIFICATION_DISMISS_DELAY_KEY = "cio_live_notification_dismiss_delay"
        const val LIVE_NOTIFICATION_SEGMENTS_KEY = "cio_live_notification_segments"
        const val LIVE_NOTIFICATION_POINTS_KEY = "cio_live_notification_points"
        const val LIVE_NOTIFICATION_START_ICON_KEY = "cio_live_notification_start_icon"
        const val LIVE_NOTIFICATION_END_ICON_KEY = "cio_live_notification_end_icon"
        const val LIVE_NOTIFICATION_TRACKER_ICON_KEY = "cio_live_notification_tracker_icon"

        // Optional — channel configuration (only needed on first push, ignored after channel exists)
        const val LIVE_NOTIFICATION_CHANNEL_ID_KEY = "cio_live_notification_channel_id"
        const val LIVE_NOTIFICATION_CHANNEL_NAME_KEY = "cio_live_notification_channel_name"
        const val LIVE_NOTIFICATION_CHANNEL_IMPORTANCE_KEY = "cio_live_notification_channel_importance"

        private const val TYPE_PROGRESS = "progress"
        private const val TYPE_COUNTDOWN = "countdown"
        private const val TYPE_TEXT = "text"

        private const val DEFAULT_DISMISS_DELAY_MS = 3000L
    }

    fun handle(
        context: Context,
        deliveryId: String,
        deliveryToken: String,
        liveNotificationId: String,
        title: String,
        body: String,
        @DrawableRes smallIcon: Int,
        @ColorInt tintColor: Int?,
        channelId: String,
        notificationManager: NotificationManager
    ) {
        val status = bundle.getString(LIVE_NOTIFICATION_STATUS_KEY) ?: "updated"
        val segmentsJson = bundle.getString(LIVE_NOTIFICATION_SEGMENTS_KEY)
        val pointsJson = bundle.getString(LIVE_NOTIFICATION_POINTS_KEY)
        val progress = bundle.getString(LIVE_NOTIFICATION_PROGRESS_KEY)?.toIntOrNull() ?: 0
        val progressMax = bundle.getString(LIVE_NOTIFICATION_PROGRESS_MAX_KEY)?.toIntOrNull() ?: 100
        val subText = bundle.getString(LIVE_NOTIFICATION_SUBTEXT_KEY)
        val backgroundColor = bundle.getString(LIVE_NOTIFICATION_COLOR_KEY)?.toColorOrNull()
        val colorized = bundle.getString(LIVE_NOTIFICATION_COLORIZED_KEY) == "true"
        val startIconRes = context.getDrawableByName(bundle.getString(LIVE_NOTIFICATION_START_ICON_KEY))
        val endIconRes = context.getDrawableByName(bundle.getString(LIVE_NOTIFICATION_END_ICON_KEY))
        val trackerIconRes = context.getDrawableByName(bundle.getString(LIVE_NOTIFICATION_TRACKER_ICON_KEY))

        // New v1 fields
        val type = bundle.getString(LIVE_NOTIFICATION_TYPE_KEY) ?: TYPE_PROGRESS
        val largeIconUrl = bundle.getString(LIVE_NOTIFICATION_LARGE_ICON_KEY)
        val dismissDelay = bundle.getString(LIVE_NOTIFICATION_DISMISS_DELAY_KEY)?.toLongOrNull()
            ?: DEFAULT_DISMISS_DELAY_MS
        val actionsJson = bundle.getString(LIVE_NOTIFICATION_ACTIONS_KEY)

        // Derive rendering flags from explicit type
        val showProgress = type == TYPE_PROGRESS
        val countdownUntil = if (type == TYPE_COUNTDOWN) {
            bundle.getString(LIVE_NOTIFICATION_COUNTDOWN_UNTIL_KEY)?.toLongOrNull()
        } else {
            null
        }

        // Download large icon if URL is provided
        val largeIcon: Bitmap? = largeIconUrl?.let { url -> downloadBitmap(url) }

        val notifId = liveNotificationId.hashCode() and 0x7FFFFFFF

        bundle.putInt(CustomerIOPushNotificationHandler.NOTIFICATION_REQUEST_CODE, notifId)

        val payload = CustomerIOParsedPushPayload(
            extras = Bundle(bundle),
            deepLink = bundle.getString(CustomerIOPushNotificationHandler.DEEP_LINK_KEY),
            cioDeliveryId = deliveryId,
            cioDeliveryToken = deliveryToken,
            title = title,
            body = body,
            liveNotificationId = liveNotificationId
        )

        val pendingIntent = createIntentForNotificationClick(context, notifId, payload)

        // Parse action buttons
        val actions = parseActions(context, actionsJson, liveNotificationId, payload)

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

        notificationManager.notify(liveNotificationId, notifId, notification)

        if (status == "ended") {
            Handler(Looper.getMainLooper()).postDelayed({
                notificationManager.cancel(liveNotificationId, notifId)
            }, dismissDelay)
        }
    }

    private fun downloadBitmap(imageUrl: String): Bitmap? = runBlocking {
        withContext(Dispatchers.IO) {
            try {
                val input = URL(imageUrl).openStream()
                BitmapFactory.decodeStream(input)
            } catch (e: Exception) {
                SDKComponent.logger.error("Failed to download live notification large icon from '$imageUrl': ${e.message}")
                null
            }
        }
    }

    private fun parseActions(
        context: Context,
        actionsJson: String?,
        liveNotificationId: String,
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

                // Create a payload copy with this action's deep link
                val actionPayload = CustomerIOParsedPushPayload(
                    extras = payload.extras,
                    deepLink = link.takeIf { it.isNotEmpty() },
                    cioDeliveryId = payload.cioDeliveryId,
                    cioDeliveryToken = payload.cioDeliveryToken,
                    title = payload.title,
                    body = payload.body,
                    liveNotificationId = liveNotificationId
                )

                val requestCode = (liveNotificationId.hashCode() + i + 1) and 0x7FFFFFFF
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
