package io.customer.messagingpush

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat

/**
 * Parameters for building a live notification on API 21-23 using a standard
 * [NotificationCompat] progress bar.
 *
 * This tier supports:
 * - Standard linear progress bar (determinate)
 * - Tint color and background color for notification chrome
 * - Colorized mode (tints the entire notification background)
 * - Title, body, and subtext via standard notification fields
 *
 * Not supported on this tier (use higher API levels):
 * - Segmented progress bar
 * - Progress points
 * - Start, end, or tracker icons
 * - Custom view layout
 * - Promoted live update status
 */
internal data class BasicNotificationParams(
    val context: Context,
    val channelId: String,
    val title: String,
    val body: String,
    val subText: String?,
    @DrawableRes val smallIcon: Int,
    @ColorInt val tintColor: Int?,
    @ColorInt val backgroundColor: Int?,
    val colorized: Boolean,
    val progress: Int,
    val progressMax: Int,
    val pendingIntent: PendingIntent?,
    val countdownUntil: Long?,
    val largeIcon: Bitmap?,
    val actions: List<ActionData>,
    val showProgress: Boolean
)

/**
 * Builds live notifications using a standard [NotificationCompat] progress bar
 * for API 21-23.
 *
 * This is the most basic tier with no custom views or segmented progress.
 * It provides a simple linear progress bar with colorized background support.
 */
internal object BasicNotificationBuilder {

    private const val MAX_ACTIONS = 3

    fun build(params: BasicNotificationParams): Notification {
        val category = if (params.showProgress) {
            NotificationCompat.CATEGORY_PROGRESS
        } else {
            NotificationCompat.CATEGORY_STATUS
        }

        val builder = NotificationCompat.Builder(params.context, params.channelId)
            .setSmallIcon(params.smallIcon)
            .setContentTitle(params.title)
            .setContentText(params.body)
            .setOngoing(true)
            .setCategory(category)
            .setStyle(NotificationCompat.BigTextStyle().bigText(params.body))

        if (params.showProgress) {
            val safeProgress = params.progress.coerceIn(0, params.progressMax)
            builder.setProgress(params.progressMax, safeProgress, false)
        }

        // Countdown timer
        params.countdownUntil?.let { until ->
            builder.setWhen(until)
            builder.setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setChronometerCountDown(true)
            }
            builder.setShowWhen(true)
        }

        // Large icon
        params.largeIcon?.let { builder.setLargeIcon(it) }

        params.subText?.let { builder.setSubText(it) }
        params.tintColor?.let { builder.setColor(it) }
        params.backgroundColor?.let { builder.setColor(it) }
        if (params.colorized) {
            builder.setColorized(true)
        }
        params.pendingIntent?.let { builder.setContentIntent(it) }

        // Action buttons (Android supports max 3)
        val actionCount = minOf(params.actions.size, MAX_ACTIONS)
        for (i in 0 until actionCount) {
            val action = params.actions[i]
            builder.addAction(0, action.label, action.pendingIntent)
        }

        return builder.build()
    }
}
