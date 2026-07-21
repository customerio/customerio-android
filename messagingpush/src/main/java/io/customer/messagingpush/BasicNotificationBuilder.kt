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
 * Parameters for building a live notification on pre-API 36 devices using
 * standard [NotificationCompat] styles (no segments, points, or promoted status).
 */
internal data class BasicNotificationParams(
    val context: Context,
    val channelId: String,
    val title: String,
    val body: String,
    val subText: String?,
    @DrawableRes val smallIcon: Int,
    @ColorInt val accentColor: Int?,
    val colorized: Boolean,
    val progress: Int,
    val progressMax: Int,
    val pendingIntent: PendingIntent?,
    val deleteIntent: PendingIntent?,
    val countdownUntil: Long?,
    val largeIcon: Bitmap?,
    val showProgress: Boolean,
    val ongoing: Boolean = true
)

/**
 * Builds live notifications for pre-API 36 devices using standard
 * [NotificationCompat] styles with a native progress bar.
 */
internal object BasicNotificationBuilder {

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
            .setOngoing(params.ongoing)
            .setAutoCancel(!params.ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(category)
            .setStyle(NotificationCompat.BigTextStyle().bigText(params.body))

        if (params.showProgress) {
            val safeProgress = params.progress.coerceIn(0, params.progressMax)
            builder.setProgress(params.progressMax, safeProgress, false)
        }

        // Only count down to a future instant; a past target can suppress the notification.
        params.countdownUntil?.takeIf { it > System.currentTimeMillis() }?.let { until ->
            builder.setWhen(until)
            builder.setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setChronometerCountDown(true)
            }
            builder.setShowWhen(true)
        }

        params.largeIcon?.let { builder.setLargeIcon(it) }

        params.subText?.let { builder.setSubText(it) }
        params.accentColor?.let { builder.setColor(it) }
        if (params.colorized) {
            builder.setColorized(true)
        }
        params.pendingIntent?.let { builder.setContentIntent(it) }
        params.deleteIntent?.let { builder.setDeleteIntent(it) }

        return builder.build()
    }
}
