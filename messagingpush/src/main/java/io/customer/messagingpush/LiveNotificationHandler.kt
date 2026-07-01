package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.messagingpush.di.pushModuleConfig
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.livenotification.LiveNotificationDismissReceiver
import io.customer.messagingpush.livenotification.template.TemplateAssets
import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.livenotification.template.TemplateRenderResult
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.core.di.SDKComponent
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Dispatches templated live notifications.
 *
 * Live notifications are ongoing notifications that can be updated in-place
 * (the Android counterpart of iOS Live Activities). Each push declares a
 * `notification_type` (one of the closed set in [TemplateRegistry], prefixed
 * with `io.customer.liveactivities.`). Template fields arrive either flattened
 * at the envelope top level or nested under a `payload` object ([extractData]
 * handles both). Pushes share a stable [ACTIVITY_ID_KEY] so successive updates
 * replace the previous notification rather than creating new ones.
 */
internal class LiveNotificationHandler(
    private val bundle: Bundle
) {

    companion object {
        const val ACTIVITY_ID_KEY = "activity_id"
        const val EVENT_KEY = "event"
        const val NOTIFICATION_TYPE_KEY = "notification_type"
        const val TIMESTAMP_KEY = "timestamp"
        const val DISMISSAL_DATE_KEY = "dismissal_date"

        // The backend nests the template fields under a `payload` object; [extractData]
        // unwraps it. (Local-start still delivers them flattened at the top level.)
        const val PAYLOAD_KEY = "payload"

        private const val EVENT_END = "end"

        /**
         * Deterministic notification id for an [activityId] so successive
         * events (and an explicit end) address the same notification. Shared
         * with [io.customer.messagingpush.livenotification.LiveNotificationManager].
         */
        internal fun notificationId(activityId: String): Int = activityId.hashCode() and 0x7FFFFFFF

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
            NOTIFICATION_TYPE_KEY,
            TIMESTAMP_KEY,
            DISMISSAL_DATE_KEY,
            PAYLOAD_KEY,
            PushTrackingUtil.DELIVERY_ID_KEY,
            PushTrackingUtil.DELIVERY_TOKEN_KEY
        )

        // Pending `end` dismissals, keyed by activity_id, so a new event for the same
        // activity can cancel a scheduled removal (e.g. when an activity_id is reused).
        private val dismissalHandler = Handler(Looper.getMainLooper())
        private val pendingDismissals = ConcurrentHashMap<String, Runnable>()
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

        // Live notifications are opt-in: only handle activity types the host app enabled.
        val activityType = bundle.getString(NOTIFICATION_TYPE_KEY)
        if (activityType == null || activityType !in SDKComponent.pushModuleConfig.liveNotificationTypes) {
            SDKComponent.logger.debug(
                "Live notification type '$activityType' is not enabled; ignoring activity '$activityId'."
            )
            return
        }
        val isEnd = event == EVENT_END

        // Out-of-order / duplicate guard. Android renders FCM data directly, so unlike iOS
        // (where APNs/ActivityKit order updates) the SDK must drop stale pushes itself. `end`
        // is terminal and bypasses the guard so a stale `end` still cancels the notification.
        val store = SDKComponent.liveNotificationStore
        val timestamp = bundle.getString(TIMESTAMP_KEY)?.toLongOrNull()
        val lastSeen = store.lastTimestamp(activityId)
        if (!isEnd && timestamp != null) {
            if (lastSeen != null && timestamp <= lastSeen) {
                SDKComponent.logger.debug(
                    "Dropping out-of-order/duplicate live notification for '$activityId' (timestamp $timestamp <= $lastSeen)."
                )
                return
            }
        }

        // A new event for this activity supersedes any scheduled end-dismissal (handles
        // activity_id reuse where the delayed cancel would otherwise kill the new notification).
        cancelPendingDismissal(activityId)

        // Advance the high-water timestamp for ALL events (incl. `end`) so a later stale
        // update — even one arriving after `end` — is dropped by the guard above. Only ever
        // move it forward: a stale, out-of-order `end` (which bypasses the guard) must not
        // lower the mark, or a later stale update could slip through and resurrect the activity.
        if (timestamp != null && (lastSeen == null || timestamp > lastSeen)) {
            store.setLastTimestamp(activityId, timestamp)
        }

        val template = TemplateRegistry.find(activityType)
        val data = extractData(bundle)
        val branding = SDKComponent.pushModuleConfig.liveNotificationBranding
        val effectiveSmallIcon = resolveSmallIcon(context, branding, smallIcon)

        val result = template?.render(
            context = context,
            data = data,
            branding = branding,
            smallIcon = effectiveSmallIcon,
            fallbackTintColor = tintColor
        )?.let { rendered ->
            // The brand logo fills the color large-icon slot when the active template
            // didn't set one of its own. (The small icon is handled separately above
            // via the drawable-only logoDrawableName.) Skip when the activity is about
            // to be cancelled so we don't resolve/download a logo for nothing.
            val brandingLogoKey = branding?.logoAssetKey
            if (!rendered.cancelImmediately && rendered.largeIcon == null && !brandingLogoKey.isNullOrBlank()) {
                rendered.copy(largeIcon = TemplateAssets.resolveBitmap(context, brandingLogoKey))
            } else {
                rendered
            }
        }

        val notifId = notificationId(activityId)

        if (result?.cancelImmediately == true) {
            notificationManager.cancel(activityId, notifId)
            return
        }

        bundle.putInt(CustomerIOPushNotificationHandler.NOTIFICATION_REQUEST_CODE, notifId)
        val parsedPayload = CustomerIOParsedPushPayload(
            extras = Bundle(bundle),
            deepLink = result?.deepLink ?: bundle.getString(CustomerIOPushNotificationHandler.DEEP_LINK_KEY),
            cioDeliveryId = deliveryId,
            cioDeliveryToken = deliveryToken,
            title = result?.title ?: bundle.getString(CustomerIOPushNotificationHandler.TITLE_KEY).orEmpty(),
            body = result?.body ?: bundle.getString(CustomerIOPushNotificationHandler.BODY_KEY).orEmpty(),
            activityId = activityId
        )
        val pendingIntent = createIntentForNotificationClick(context, notifId, parsedPayload)
        val deletePendingIntent = createDeleteIntent(context, notifId, activityId, activityType)

        // The host app may fully render the notification; otherwise fall back to the
        // SDK template. Custom (template-less) types must be rendered by the callback.
        val appNotification = SDKComponent.pushModuleConfig.notificationCallback
            ?.createLiveNotification(parsedPayload, context)
        val notification = appNotification ?: result?.let {
            buildSdkNotification(context, channelId, effectiveSmallIcon, it, pendingIntent, deletePendingIntent)
        }

        when {
            notification != null -> {
                notificationManager.notify(activityId, notifId, notification)
                // Remember the type so the host can later end this activity with just its id.
                store.setActivityType(activityId, activityType)
            }
            // An `end` with no renderer still falls through to cancel the existing notification.
            !isEnd -> {
                // template != null but result == null ⇒ the payload lacked the fields the
                // template needs (e.g. content not flattened); we refuse to post a blank one.
                val reason = if (template != null) {
                    "required content fields are missing (payload not flattened, or empty)"
                } else {
                    "no built-in template and createLiveNotification returned null"
                }
                SDKComponent.logger.error(
                    "Not posting live notification '$activityId' (type '$activityType'): $reason."
                )
                return
            }
        }

        // Note: pushes are server-initiated, so the backend already knows about this
        // `start`/`update`/`end` — the handler never reports a lifecycle event. Only
        // on-device-initiated changes are reported: local start/update (via
        // LiveNotificationManager) and user dismissal (via LiveNotificationDismissReceiver).
        if (isEnd) {
            store.clearActivityType(activityId)
            scheduleEndDismissal(bundle, notificationManager, activityId, notifId)
        }
    }

    private fun buildSdkNotification(
        context: Context,
        channelId: String,
        @DrawableRes effectiveSmallIcon: Int,
        result: TemplateRenderResult,
        pendingIntent: PendingIntent,
        deletePendingIntent: PendingIntent
    ): Notification = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
            Api36LiveNotificationBuilder.build(
                Api36LiveNotificationParams(
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
                    deleteIntent = deletePendingIntent,
                    countdownUntil = result.countdownUntil,
                    largeIcon = result.largeIcon,
                    showProgress = result.showProgress
                )
            )
        }
        else -> {
            BasicNotificationBuilder.build(
                BasicNotificationParams(
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
                    deleteIntent = deletePendingIntent,
                    countdownUntil = result.countdownUntil,
                    largeIcon = result.largeIcon,
                    showProgress = result.showProgress
                )
            )
        }
    }

    /**
     * On `end`, removes the notification at the server-provided `dismissal_date`
     * (epoch ms). When absent, the activity is removed immediately — there is no
     * invented default delay. (Best-effort: a long delay does not survive
     * process death.)
     */
    private fun scheduleEndDismissal(
        bundle: Bundle,
        notificationManager: NotificationManager,
        activityId: String,
        notifId: Int
    ) {
        val dismissAtMs = bundle.getString(DISMISSAL_DATE_KEY)?.toLongOrNull()
        if (dismissAtMs == null) {
            notificationManager.cancel(activityId, notifId)
            return
        }
        val delayMs = (dismissAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val task = Runnable {
            notificationManager.cancel(activityId, notifId)
            pendingDismissals.remove(activityId)
        }
        pendingDismissals[activityId] = task
        dismissalHandler.postDelayed(task, delayMs)
    }

    /** Cancels a scheduled end-dismissal for [activityId], if any. */
    private fun cancelPendingDismissal(activityId: String) {
        pendingDismissals.remove(activityId)?.let { dismissalHandler.removeCallbacks(it) }
    }

    private fun createDeleteIntent(
        context: Context,
        requestCode: Int,
        activityId: String,
        activityType: String
    ): PendingIntent {
        // Carry the fields the `end` track event needs so the dismiss receiver can
        // report it without re-deriving them (the FCM token is read at dismiss time).
        val intent = Intent(context, LiveNotificationDismissReceiver::class.java).apply {
            putExtra(LiveNotificationDismissReceiver.EXTRA_ACTIVITY_ID, activityId)
            putExtra(LiveNotificationDismissReceiver.EXTRA_ACTIVITY_TYPE, activityType)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
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
        // Flattened template fields at the envelope top level (local-start shape).
        for (key in bundle.keySet()) {
            if (key in RESERVED_KEYS) continue
            val raw = bundle.getString(key) ?: continue
            data.put(key, coerceJsonValue(raw))
        }
        // Backend push nests the template fields under a `payload` object; merge them in.
        // Nested values take precedence over any top-level field of the same name.
        val payload = bundle.getString(PAYLOAD_KEY)?.let { coerceJsonValue(it) as? JSONObject }
        if (payload != null) {
            for (key in payload.keys()) {
                data.put(key, payload.get(key))
            }
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
