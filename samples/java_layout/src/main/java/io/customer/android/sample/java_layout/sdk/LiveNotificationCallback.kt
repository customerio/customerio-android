package io.customer.android.sample.java_layout.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.customer.android.sample.java_layout.R
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload

/**
 * Sample host-app renderer for **custom** live-notification activity types (those
 * the SDK has no built-in template for). Returns an app-built [Notification] for
 * the two custom types — [ACTIVITY_TYPE_RIDESHARE] via a custom RemoteViews layout
 * and [ACTIVITY_TYPE_WORKOUT] via the standard NotificationCompat builder — and
 * `null` for built-in types so the SDK renders those itself.
 */
class LiveNotificationCallback : CustomerIOPushNotificationCallback {

    override fun createLiveNotification(
        payload: CustomerIOParsedPushPayload,
        context: Context
    ): Notification? {
        val extras = payload.extras
        val activityType = extras.getString(KEY_ACTIVITY_TYPE) ?: return null
        val ended = extras.getString(KEY_EVENT) == EVENT_END
        ensureChannel(context)
        return when (activityType) {
            ACTIVITY_TYPE_RIDESHARE -> buildRideshare(context, extras, ended)
            ACTIVITY_TYPE_WORKOUT -> buildWorkout(context, extras, ended)
            // Not one of ours — let the SDK render its built-in template.
            else -> null
        }
    }

    // --- Custom type 1: fully custom RemoteViews layout ---

    private fun buildRideshare(context: Context, extras: Bundle, ended: Boolean): Notification {
        val driver = extras.getString("driverName") ?: "Your driver"
        val vehicle = extras.getString("vehicle") ?: ""
        val plate = extras.getString("plate") ?: ""
        val rating = extras.getString("rating") ?: ""
        val eta = extras.getString("etaText") ?: ""
        val status = extras.getString("statusMessage") ?: ""
        val step = extras.getString("step")?.toIntOrNull() ?: 0
        val progress = extras.getString("progress")?.toIntOrNull() ?: 0

        val title = if (ended) "Trip complete" else "$driver is on the way"
        val avatarInitial = driver.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val vehicleLine = listOf(vehicle, plate).filter { it.isNotBlank() }.joinToString(" · ")
        val etaText = if (ended) "Done" else eta

        fun applyHeader(rv: RemoteViews) {
            rv.setTextViewText(R.id.tv_avatar, avatarInitial)
            rv.setTextViewText(R.id.tv_title, title)
            rv.setTextViewText(R.id.tv_eta, etaText)
        }

        // Collapsed is a compact single line; expanded adds the gold star rating + vehicle.
        val collapsed = RemoteViews(context.packageName, R.layout.notification_rideshare_collapsed)
        applyHeader(collapsed)

        val expanded = RemoteViews(context.packageName, R.layout.notification_rideshare_expanded)
        applyHeader(expanded)
        expanded.setTextViewText(R.id.tv_subtitle, ratingLine(rating, vehicleLine))
        expanded.setTextViewText(R.id.tv_status, if (ended) "Thanks for riding with us" else status)

        val stepIds = intArrayOf(R.id.iv_step1, R.id.iv_step2, R.id.iv_step3, R.id.iv_step4)
        for (i in stepIds.indices) {
            val icon = when {
                ended || i < step -> R.drawable.ic_step_done
                i == step -> R.drawable.ic_step_active
                else -> R.drawable.ic_step_pending
            }
            expanded.setImageViewResource(stepIds[i], icon)
        }
        expanded.setProgressBar(R.id.progress_bar, 100, if (ended) 100 else progress, false)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rideshare_car)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setOngoing(!ended)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    /** Builds "★ 4.9 · Toyota Prius · 7XYZ123" with the star tinted gold. */
    private fun ratingLine(rating: String, vehicleLine: String): CharSequence {
        val parts = listOfNotNull(
            rating.takeIf { it.isNotBlank() }?.let { "★ $it" },
            vehicleLine.takeIf { it.isNotBlank() }
        )
        val full = parts.joinToString(" · ")
        if (rating.isBlank() || full.isEmpty()) return full
        return SpannableString(full).apply {
            setSpan(ForegroundColorSpan(0xFFF5A623.toInt()), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // --- Custom type 2: standard NotificationCompat builder API ---

    private fun buildWorkout(context: Context, extras: Bundle, ended: Boolean): Notification {
        val title = extras.getString("workoutTitle") ?: "Workout"
        val distance = extras.getString("distance") ?: ""
        val duration = extras.getString("duration") ?: ""
        val pace = extras.getString("pace") ?: ""
        val progress = extras.getString("progress")?.toIntOrNull() ?: 0
        val summary = listOf(distance, duration, pace).filter { it.isNotBlank() }.joinToString(" · ")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_workout_run)
            .setContentTitle(if (ended) "$title complete" else title)
            .setContentText(summary)
            .setProgress(100, if (ended) 100 else progress, false)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(if (ended) "$summary\nGreat job — workout saved." else "$summary\nKeep going!")
            )
            .setOngoing(!ended)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (!ended) {
            val pauseIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_WORKOUT_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_workout_run, "Pause", pauseIntent)
        }

        // Request promoted-ongoing (live-update) treatment on Android 16+ (BAKLAVA).
        if (Build.VERSION.SDK_INT >= 36 && !ended) {
            builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }

        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Custom Live Updates",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
        }
    }

    companion object {
        // Custom activity types (not built-in). Must also be passed to
        // MessagingPushModuleConfig.Builder.enableLiveNotificationTypes(...) to be enabled.
        const val ACTIVITY_TYPE_RIDESHARE = "io.customer.livenotifications.custom.rideshare"
        const val ACTIVITY_TYPE_WORKOUT = "io.customer.livenotifications.custom.workout"

        private const val CHANNEL_ID = "cio_custom_live"
        private const val KEY_ACTIVITY_TYPE = "notification_type"
        private const val KEY_EVENT = "event"
        private const val EVENT_END = "end"
        private const val ACTION_WORKOUT_PAUSE = "io.customer.android.sample.java_layout.WORKOUT_PAUSE"

        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (extension SDK 36.1) by raw value.
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
