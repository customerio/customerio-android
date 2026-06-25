package io.customer.android.sample.java_layout.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.customer.android.sample.java_layout.R
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload

/**
 * Sample host-app renderer for **custom** live-notification activity types — the
 * ones the SDK has no built-in template for. The SDK calls
 * [createLiveNotification] for every live-notification event; we return a fully
 * app-built [Notification] for the two custom types and `null` for the built-in
 * ones (so the SDK keeps rendering those from its own templates).
 *
 * Two deliberately different rendering strategies are shown:
 *  - [ACTIVITY_TYPE_RIDESHARE] → a **completely custom RemoteViews layout**
 *    (collapsed + expanded), with an app-drawn 4-stop progress strip.
 *  - [ACTIVITY_TYPE_WORKOUT] → the **standard NotificationCompat builder API**
 *    (determinate progress + BigTextStyle + action), requesting promoted-ongoing
 *    treatment on Android 16+.
 *
 * The SDK still owns posting: it keys the notification by `activity_id` (so
 * updates replace it) and cancels it on `end`.
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
        val eta = extras.getString("etaText") ?: ""
        val status = extras.getString("statusMessage") ?: ""
        val step = extras.getString("step")?.toIntOrNull() ?: 0
        val progress = extras.getString("progress")?.toIntOrNull() ?: 0

        val title = if (ended) "Trip complete" else "$driver is on the way"
        val subtitle = listOf(vehicle, plate).filter { it.isNotBlank() }.joinToString(" · ")
        val etaText = if (ended) "Done" else eta

        fun applyHeader(rv: RemoteViews) {
            rv.setTextViewText(R.id.tv_title, title)
            rv.setTextViewText(R.id.tv_subtitle, subtitle)
            rv.setTextViewText(R.id.tv_eta, etaText)
        }

        val collapsed = RemoteViews(context.packageName, R.layout.notification_rideshare_collapsed)
        applyHeader(collapsed)

        val expanded = RemoteViews(context.packageName, R.layout.notification_rideshare_expanded)
        applyHeader(expanded)
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

        // Request live-update (promoted-ongoing) treatment on Android 16+ (BAKLAVA).
        // Requires POST_PROMOTED_NOTIFICATIONS (declared in the manifest), an ongoing
        // notification with a title, an allowed style (BigTextStyle), and no colorize.
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
        const val ACTIVITY_TYPE_RIDESHARE = "io.customer.liveactivities.custom.rideshare"
        const val ACTIVITY_TYPE_WORKOUT = "io.customer.liveactivities.custom.workout"

        private const val CHANNEL_ID = "cio_custom_live"
        private const val KEY_ACTIVITY_TYPE = "notification_type"
        private const val KEY_EVENT = "event"
        private const val EVENT_END = "end"
        private const val ACTION_WORKOUT_PAUSE = "io.customer.android.sample.java_layout.WORKOUT_PAUSE"

        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (extension SDK 36.1) by raw value.
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
