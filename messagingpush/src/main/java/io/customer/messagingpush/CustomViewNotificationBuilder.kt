package io.customer.messagingpush

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import io.customer.sdk.core.di.SDKComponent
import org.json.JSONArray
import org.json.JSONException

/**
 * Parameters for building a live notification on API 24-35 using a custom
 * [RemoteViews] layout with [NotificationCompat.DecoratedCustomViewStyle].
 *
 * This tier supports:
 * - Segmented progress bar rendered via custom RemoteViews
 * - Start and end icons flanking the progress bar
 * - Background color on the notification card
 * - Title, body, and subtext rendered in the custom layout
 *
 * Not supported on this tier (use [Api36LiveNotificationParams] on API 36+):
 * - Progress points (markers at specific positions)
 * - Tracker icon (moving indicator on the bar)
 * - Promoted live update status
 */
internal data class CustomViewNotificationParams(
    val context: Context,
    val channelId: String,
    val title: String,
    val body: String,
    val subText: String?,
    @DrawableRes val smallIcon: Int,
    @ColorInt val accentColor: Int?,
    val segmentsJson: String?,
    val progress: Int,
    val progressMax: Int,
    @DrawableRes val startIconRes: Int?,
    @DrawableRes val endIconRes: Int?,
    val pendingIntent: PendingIntent?,
    val countdownUntil: Long?,
    val largeIcon: Bitmap?,
    val actions: List<ActionData>,
    val showProgress: Boolean
)

/**
 * Builds live notifications using a custom [RemoteViews] segmented progress bar
 * with [NotificationCompat.DecoratedCustomViewStyle] on API 24-35.
 *
 * Title, body, subtext, and background color are rendered directly in the
 * custom view for full visual control.
 */
internal object CustomViewNotificationBuilder {

    private const val MAX_ACTIONS = 3

    @RequiresApi(Build.VERSION_CODES.N)
    fun build(params: CustomViewNotificationParams): Notification {
        val pkg = params.context.packageName
        val contentView = RemoteViews(pkg, R.layout.cio_notification_live_progress)

        if (params.accentColor != null) {
            contentView.setInt(R.id.cio_live_root, "setBackgroundColor", params.accentColor)
        }

        if (params.title.isNotEmpty()) {
            contentView.setTextViewText(R.id.cio_live_title, params.title)
            contentView.setViewVisibility(R.id.cio_live_title, android.view.View.VISIBLE)
        }

        if (params.body.isNotEmpty()) {
            contentView.setTextViewText(R.id.cio_live_body, params.body)
            contentView.setViewVisibility(R.id.cio_live_body, android.view.View.VISIBLE)
        }

        if (!params.subText.isNullOrEmpty()) {
            contentView.setTextViewText(R.id.cio_live_subtext, params.subText)
            contentView.setViewVisibility(R.id.cio_live_subtext, android.view.View.VISIBLE)
        }

        if (params.showProgress) {
            if (params.startIconRes != null) {
                contentView.setImageViewResource(R.id.cio_start_icon, params.startIconRes)
                contentView.setViewVisibility(R.id.cio_start_icon, android.view.View.VISIBLE)
            }

            if (params.endIconRes != null) {
                contentView.setImageViewResource(R.id.cio_end_icon, params.endIconRes)
                contentView.setViewVisibility(R.id.cio_end_icon, android.view.View.VISIBLE)
            }

            val segmentCount = parseSegmentCount(params.segmentsJson, params.progressMax)
            val safeProgress = params.progress.coerceIn(0, segmentCount)

            contentView.removeAllViews(R.id.cio_segments_container)
            contentView.setViewVisibility(R.id.cio_segments_container, android.view.View.VISIBLE)
            for (i in 0 until segmentCount) {
                val segmentView = RemoteViews(pkg, R.layout.cio_notification_live_segment)
                val backgroundRes = when {
                    i < safeProgress - 1 -> R.drawable.cio_live_segment_filled
                    i == safeProgress - 1 -> R.drawable.cio_live_segment_active
                    else -> R.drawable.cio_live_segment_unfilled
                }
                segmentView.setInt(
                    R.id.cio_segment_view,
                    "setBackgroundResource",
                    backgroundRes
                )
                contentView.addView(R.id.cio_segments_container, segmentView)
            }
        } else {
            // Hide progress-related views when progress is not shown
            contentView.setViewVisibility(R.id.cio_segments_container, android.view.View.GONE)
            contentView.setViewVisibility(R.id.cio_start_icon, android.view.View.GONE)
            contentView.setViewVisibility(R.id.cio_end_icon, android.view.View.GONE)
        }

        val category = if (params.showProgress) {
            NotificationCompat.CATEGORY_PROGRESS
        } else {
            NotificationCompat.CATEGORY_STATUS
        }

        val builder = NotificationCompat.Builder(params.context, params.channelId)
            .setSmallIcon(params.smallIcon)
            .setOngoing(true)
            .setCategory(category)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(contentView)

        // Countdown timer (shown in notification header)
        params.countdownUntil?.let { until ->
            builder.setWhen(until)
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setShowWhen(true)
        }

        // Large icon
        params.largeIcon?.let { builder.setLargeIcon(it) }

        params.accentColor?.let { builder.setColor(it) }
        params.pendingIntent?.let { builder.setContentIntent(it) }

        // Action buttons (Android supports max 3)
        val actionCount = minOf(params.actions.size, MAX_ACTIONS)
        for (i in 0 until actionCount) {
            val action = params.actions[i]
            builder.addAction(0, action.label, action.pendingIntent)
        }

        return builder.build()
    }

    private fun parseSegmentCount(segmentsJson: String?, progressMax: Int): Int {
        if (segmentsJson != null) {
            try {
                return JSONArray(segmentsJson).length()
            } catch (e: JSONException) {
                SDKComponent.logger.error("Failed to parse live notification segments JSON: ${e.message}")
            }
        }
        return if (progressMax > 0) progressMax else 4
    }
}
