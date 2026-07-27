package io.customer.messagingpush

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.customer.messagingpush.livenotification.template.PointSpec
import io.customer.messagingpush.livenotification.template.SegmentSpec
import io.customer.sdk.core.di.SDKComponent

/** Parameters for building a promoted live notification on API 36+ (BAKLAVA). */
internal data class Api36LiveNotificationParams(
    val context: Context,
    val channelId: String,
    val title: String,
    val body: String,
    val subText: String?,
    @DrawableRes val smallIcon: Int,
    @ColorInt val accentColor: Int?,
    val segments: List<SegmentSpec>,
    val points: List<PointSpec>,
    val progress: Int,
    val progressMax: Int,
    @DrawableRes val startIconRes: Int?,
    @DrawableRes val endIconRes: Int?,
    @DrawableRes val trackerIconRes: Int?,
    val pendingIntent: PendingIntent?,
    val deleteIntent: PendingIntent?,
    val countdownUntil: Long?,
    val largeIcon: Bitmap?,
    val showProgress: Boolean,
    val ongoing: Boolean = true
)

/** Builds promoted live notifications on API 36+ (BAKLAVA). */
internal object Api36LiveNotificationBuilder {

    // Raw string value (EXTRA_REQUEST_PROMOTED_ONGOING is only in extension SDK 36.1).
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val POST_PROMOTED_NOTIFICATIONS_PERMISSION =
        "android.permission.POST_PROMOTED_NOTIFICATIONS"

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun build(params: Api36LiveNotificationParams): Notification {
        val builder = Notification.Builder(params.context, params.channelId)
            .setSmallIcon(params.smallIcon)
            .setContentTitle(params.title)
            .setContentText(params.body)
            .setOngoing(params.ongoing)
            .setAutoCancel(!params.ongoing)
            .setOnlyAlertOnce(true)

        if (params.ongoing) {
            if (canPostPromotedNotifications(params.context)) {
                val extras = Bundle().apply {
                    putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                }
                builder.addExtras(extras)
            } else {
                SDKComponent.logger.debug(
                    "POST_PROMOTED_NOTIFICATIONS not granted; posting as a standard ongoing " +
                        "notification. Declare <uses-permission " +
                        "android:name=\"android.permission.POST_PROMOTED_NOTIFICATIONS\" /> in the " +
                        "app manifest to get the promoted live-update treatment on Android 16+."
                )
            }
        }

        if (params.showProgress) {
            builder.setCategory(Notification.CATEGORY_PROGRESS)

            val effectiveSegments = if (params.segments.isNotEmpty()) {
                params.segments.map { it.toSystem() }
            } else {
                listOf(Notification.ProgressStyle.Segment(params.progressMax.coerceAtLeast(1)))
            }
            val maxProgress = effectiveSegments.sumOf { it.length }
            val safeProgress = params.progress.coerceIn(0, maxProgress)

            val progressStyle = Notification.ProgressStyle()
                .setProgress(safeProgress)

            progressStyle.progressSegments = effectiveSegments

            if (params.points.isNotEmpty()) {
                progressStyle.progressPoints = params.points.map { it.toSystem(maxProgress) }
            }

            params.startIconRes?.let { res ->
                progressStyle.setProgressStartIcon(Icon.createWithResource(params.context, res))
            }
            params.endIconRes?.let { res ->
                progressStyle.setProgressEndIcon(Icon.createWithResource(params.context, res))
            }
            params.trackerIconRes?.let { res ->
                progressStyle.setProgressTrackerIcon(Icon.createWithResource(params.context, res))
            }

            builder.style = progressStyle
        } else {
            builder.setCategory(Notification.CATEGORY_STATUS)
            builder.style = Notification.BigTextStyle().bigText(params.body)
        }

        // Only count down to a future instant; a past target can suppress the notification.
        params.countdownUntil?.takeIf { it > System.currentTimeMillis() }?.let { until ->
            builder.setWhen(until)
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setShowWhen(true)
        }

        params.largeIcon?.let { bitmap ->
            builder.setLargeIcon(Icon.createWithBitmap(bitmap))
        }

        params.subText?.let { builder.setSubText(it) }
        params.accentColor?.let { builder.setColor(it) }
        params.pendingIntent?.let { builder.setContentIntent(it) }
        params.deleteIntent?.let { builder.setDeleteIntent(it) }

        return builder.build()
    }

    private fun canPostPromotedNotifications(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            POST_PROMOTED_NOTIFICATIONS_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun SegmentSpec.toSystem(): Notification.ProgressStyle.Segment {
        val segment = Notification.ProgressStyle.Segment(length.coerceAtLeast(1))
        color?.let { segment.color = it }
        return segment
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun PointSpec.toSystem(maxProgress: Int): Notification.ProgressStyle.Point {
        val point = Notification.ProgressStyle.Point(position.coerceIn(0, maxProgress))
        color?.let { point.color = it }
        return point
    }
}
