package io.customer.messagingpush

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import io.customer.sdk.core.di.SDKComponent
import org.json.JSONArray
import org.json.JSONException
import androidx.core.graphics.toColorInt

/**
 * Parameters for building a promoted live notification on API 36+ (BAKLAVA).
 *
 * When [showProgress] is true, uses [Notification.ProgressStyle] with full
 * segmented progress bar support. When false, uses [Notification.BigTextStyle]
 * for text-only live updates (sports scores, auction bids, etc.).
 *
 * Both modes request promoted ongoing status for live update treatment.
 */
internal data class Api36LiveNotificationParams(
    val context: Context,
    val channelId: String,
    val title: String,
    val body: String,
    val subText: String?,
    @DrawableRes val smallIcon: Int,
    @ColorInt val accentColor: Int?,
    val segmentsJson: String?,
    val pointsJson: String?,
    val progress: Int,
    val progressMax: Int,
    @DrawableRes val startIconRes: Int?,
    @DrawableRes val endIconRes: Int?,
    @DrawableRes val trackerIconRes: Int?,
    val pendingIntent: PendingIntent?,
    val countdownUntil: Long?,
    val largeIcon: Bitmap?,
    val actions: List<ActionData>,
    val showProgress: Boolean
)

/**
 * Builds promoted live notifications on API 36+ (BAKLAVA).
 *
 * Uses [Notification.ProgressStyle] for progress-based notifications and
 * [Notification.BigTextStyle] for text-only live updates. Both styles are
 * valid for promoted live updates per Android documentation.
 *
 * Requirements for promoted live updates (customer responsibility):
 * - App manifest must declare `android.permission.POST_PROMOTED_NOTIFICATIONS`
 * - Notification must not be colorized (this builder does not call setColorized)
 * - Notification must use an allowed style (ProgressStyle or BigTextStyle)
 * - Notification must have a title and be ongoing
 */
internal object Api36LiveNotificationBuilder {

    private const val MAX_ACTIONS = 3

    // Notification.EXTRA_REQUEST_PROMOTED_ONGOING was added in extension SDK 36.1.
    // Use the raw string value so we can compile against base API 36.
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun build(params: Api36LiveNotificationParams): Notification {
        val builder = Notification.Builder(params.context, params.channelId)
            .setSmallIcon(params.smallIcon)
            .setContentTitle(params.title)
            .setContentText(params.body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        // Request promoted ongoing status for live update treatment.
        // Requires android.permission.POST_PROMOTED_NOTIFICATIONS in the app manifest.
        builder.addExtras(Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        })

        if (params.showProgress) {
            builder.setCategory(Notification.CATEGORY_PROGRESS)

            val segments = params.segmentsJson?.let { parseSegments(it) } ?: emptyList()
            // When no segments are provided, create a single segment sized to progressMax
            // so ProgressStyle knows the total range for the bar.
            val effectiveSegments = segments.ifEmpty {
                listOf(Notification.ProgressStyle.Segment(params.progressMax))
            }
            val maxProgress = effectiveSegments.sumOf { it.length }
            val safeProgress = params.progress.coerceIn(0, maxProgress)

            val progressStyle = Notification.ProgressStyle()
                .setProgress(safeProgress)

            progressStyle.progressSegments = effectiveSegments

            params.pointsJson?.let { json ->
                val points = parsePoints(json, maxProgress)
                if (points.isNotEmpty()) {
                    progressStyle.progressPoints = points
                }
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

        // Countdown timer
        params.countdownUntil?.let { until ->
            builder.setWhen(until)
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setShowWhen(true)
        }

        // Large icon
        params.largeIcon?.let { bitmap ->
            builder.setLargeIcon(Icon.createWithBitmap(bitmap))
        }

        params.subText?.let { builder.setSubText(it) }
        params.accentColor?.let { builder.setColor(it) }
        params.pendingIntent?.let { builder.setContentIntent(it) }

        // Action buttons (Android supports max 3)
        val actionCount = minOf(params.actions.size, MAX_ACTIONS)
        for (i in 0 until actionCount) {
            val action = params.actions[i]
            builder.addAction(
                Notification.Action.Builder(null, action.label, action.pendingIntent).build()
            )
        }

        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun parseSegments(json: String): List<Notification.ProgressStyle.Segment> {
        val segments = mutableListOf<Notification.ProgressStyle.Segment>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val length = obj.optInt("length", 1).coerceAtLeast(1)
                val segment = Notification.ProgressStyle.Segment(length)
                val colorStr = obj.optString("color", "")
                if (colorStr.isNotEmpty()) {
                    try {
                        segment.color = colorStr.toColorInt()
                    } catch (e: IllegalArgumentException) {
                        SDKComponent.logger.error("Invalid segment color '$colorStr': ${e.message}")
                    }
                }
                segments.add(segment)
            }
        } catch (e: JSONException) {
            SDKComponent.logger.error("Failed to parse live notification segments JSON: ${e.message}")
        }
        return segments
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun parsePoints(json: String, maxProgress: Int): List<Notification.ProgressStyle.Point> {
        val points = mutableListOf<Notification.ProgressStyle.Point>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val position = obj.optInt("position", 0).coerceIn(0, maxProgress)
                val point = Notification.ProgressStyle.Point(position)
                val colorStr = obj.optString("color", "")
                if (colorStr.isNotEmpty()) {
                    try {
                        point.color = colorStr.toColorInt()
                    } catch (e: IllegalArgumentException) {
                        SDKComponent.logger.error("Invalid point color '$colorStr': ${e.message}")
                    }
                }
                points.add(point)
            }
        } catch (e: JSONException) {
            SDKComponent.logger.error("Failed to parse live notification points JSON: ${e.message}")
        }
        return points
    }
}
